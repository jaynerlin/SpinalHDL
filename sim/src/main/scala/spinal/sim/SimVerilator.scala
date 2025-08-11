/**
 * Verilator仿真器Scala接口实现文件
 *
 * 文件作用：
 * 这个文件实现了SpinalHDL与Verilator编译的C++模型之间的Scala接口层。
 * 它提供了类型安全的信号访问方法，并处理不同数据宽度的信号读写。
 *
 * 在仿真流程中的位置：
 * 用户仿真代码 -> SimManager -> SimVerilator -> JNI -> Verilator C++模型
 *
 * 主要功能：
 * - 实现SimRaw接口，提供底层仿真器访问
 * - 根据信号宽度选择合适的数据访问策略（32位/64位/大整数）
 * - 处理有符号和无符号数据类型的转换
 * - 提供内存信号的索引访问支持
 * - 管理仿真控制（时钟推进、波形控制等）
 *
 * 核心设计：
 * - 所有信号访问都通过JNI调用到C++层
 * - 根据信号宽度自动选择最优的数据表示方式
 * - 支持超过64位的大整数信号处理
 * - 提供缓冲写入优化（Verilator中为false）
 */

package spinal.sim

/**
 * SimVerilator伴生对象
 * 包含Verilator仿真相关的常量定义
 */
object SimVerilator{
  final val bigInt32b = BigInt("FFFFFFFFFFFFFFFF",16)  // 64位掩码常量
}

/**
 * Verilator仿真器实现类
 * 提供与Verilator编译的C++模型的接口
 * @param backend Verilator后端实例
 * @param handle 本地仿真句柄
 */
class SimVerilator(backend : VerilatorBackend,
                   handle : Long) extends SimRaw(){

  /**
   * 从内存中读取整数信号值
   * @param signal 要读取的信号
   * @param index 内存索引
   * @return 信号的整数值
   */
  override def getIntMem(signal : Signal,
                      index : Long) : Int = {
    assert(signal.id != -1, "You can't access this signal in the simulation, as it isn't public")
    signal.dataType.raw64ToInt(backend.nativeInstance.getU64_mem(handle,
                                                                 signal.id,
                                                                 index), signal : Signal)
  }

  /**
   * 向内存中写入整数信号值
   * @param signal 要写入的信号
   * @param value 要写入的值
   * @param index 内存索引
   */
  def setIntMem(signal : Signal,
                 value : Int,
                 index : Long) : Unit = {
    setLongMem(signal, value, index)
  }

  /**
   * 从内存中读取长整数信号值
   * @param signal 要读取的信号
   * @param index 内存索引
   * @return 信号的长整数值
   */
  override def getLongMem(signal : Signal,
                          index : Long) : Long = {
    assert(signal.id != -1, "You can't access this signal in the simulation, as it isn't public")
    signal.dataType.raw64ToLong(backend.nativeInstance.getU64_mem(handle,
                                                                  signal.id,
                                                                  index), signal : Signal)
  }
  /**
   * 向内存中写入长整数信号值
   * @param signal 要写入的信号
   * @param value 要写入的值
   * @param index 内存索引
   */
  override def setLongMem(signal : Signal,
                          value : Long,
                          index : Long) : Unit = {
    assert(signal.id != -1, "You can't access this signal in the simulation, as it isn't public")
    backend.nativeInstance.setU64_mem(handle,
                                      signal.id,
                                      signal.dataType.longToRaw64(value, signal : Signal),
                                      index)
  }

  /**
   * 从内存中读取大整数信号值
   * 根据信号宽度选择不同的读取策略
   * @param signal 要读取的信号
   * @param index 内存索引
   * @return 信号的大整数值
   */
  override def getBigIntMem(signal: Signal,
                         index : Long) = {
    if(signal.dataType.width < 64 || (signal.dataType.width == 64 && signal.dataType.isInstanceOf[SIntDataType])) {
      // 对于小于64位或64位有符号数，使用长整数读取
      getLongMem(signal, index)
    } else if(signal.dataType.width == 64){
      // 对于64位无符号数，特殊处理负值
      val rawValue = backend.nativeInstance.getU64_mem(handle,
                                                       signal.id,
                                                       index)
      if(rawValue >= 0 ) {
        BigInt(rawValue)
      }else{
        BigInt(rawValue + 1) + SimVerilator.bigInt32b
      }
    } else {
      // 对于超过64位的信号，使用字节数组读取
      if(signal.dataType.isInstanceOf[SIntDataType]){
        val array = new Array[Byte]((signal.dataType.width+31)/32*4)
        backend.nativeInstance.getAU8_mem(handle,
                                          signal.id,
                                          array,
                                          index)
        BigInt(array)
      }else{
        val array = new Array[Byte]((signal.dataType.width+31)/32*4 + 1)
        backend.nativeInstance.getAU8_mem(handle,
                                          signal.id,
                                          array,
                                          index)
        array(0) = 0  // 确保无符号数的符号位为0
        BigInt(array)
      }
    }
  }

  /**
   * 向内存中写入大整数信号值
   * 根据值的大小选择不同的写入策略
   * @param signal 要写入的信号
   * @param value 要写入的大整数值
   * @param index 内存索引
   */
  override def setBigIntMem(signal : Signal,
                         value : BigInt,
                         index : Long): Unit = {
    val valueBitLength = value.bitLength + (if(value.signum == -1) 1 else 0)
    if(valueBitLength <= 63) {
      // 对于小于等于63位的值，使用长整数写入
      setLongMem(signal,
                 value.toLong,
                 index)
    } else if(valueBitLength == 64 && signal.dataType.width == 64) {
      // 对于64位值，直接写入
      assert(signal.id != -1, "You can't access this signal in the simulation, as it isn't public")
      val valueLong = value.toLong
      signal.dataType.checkIs64(valueLong, signal : Signal)
      backend.nativeInstance.setU64_mem(handle, signal.id, valueLong, index)
    } else {
      // 对于超过64位的值，使用字节数组写入
      signal.dataType.checkBigIntRange(value, signal)
      val array = value.toByteArray
      backend.nativeInstance.setAU8_mem(handle,
                                        signal.id,
                                        array,
                                        array.length,
                                        index)
    }
  }

  // 非内存访问的信号读写方法（索引为0的内存访问）
  override def getInt(signal : Signal) : Int = { getIntMem(signal, 0) }
  def setInt(signal : Signal, value : Int) : Unit = { setLongMem(signal, value, 0) }
  override def getLong(signal : Signal) : Long = { getLongMem(signal, 0) }
  override def setLong(signal : Signal, value : Long) : Unit = { setLongMem(signal, value, 0) }
  override def getBigInt(signal : Signal) : BigInt = { getBigIntMem(signal, 0) }
  override def setBigInt(signal : Signal, value : BigInt) : Unit = { setBigIntMem(signal, value, 0) }

  // 仿真控制方法
  override def eval() : Boolean = backend.nativeInstance.eval(handle)                    // 评估仿真一个周期
  override def getTimePrecision(): Int = backend.nativeInstance.get_time_precision(handle) // 获取时间精度
  override def sleep(cycles : Long) = backend.nativeInstance.sleep(handle, cycles)       // 休眠指定周期数
  override def end() = backend.nativeInstance.synchronized(backend.nativeInstance.deleteHandle(handle)) // 结束仿真
  override def isBufferedWrite : Boolean = false                                         // Verilator不使用缓冲写入
  override def enableWave(): Unit = backend.nativeInstance.enableWave(handle)           // 启用波形记录
  override def disableWave(): Unit =  backend.nativeInstance.disableWave(handle)        // 禁用波形记录
}

