/**
 * 仿真器原始接口定义文件
 *
 * 文件作用：
 * 定义了SpinalHDL仿真系统中所有仿真器后端必须实现的统一接口。
 * 这是一个抽象层，隐藏了不同仿真器（Verilator、GHDL、VCS等）的实现细节。
 *
 * 在仿真流程中的位置：
 * SimManager -> SimRaw接口 -> 具体实现(SimVerilator/SimVpi/SimXSim) -> 仿真器
 *
 * 设计理念：
 * "Raw"表示这是最底层、最原始的仿真接口，直接映射到仿真器的内部表示，
 * 提供最小化抽象开销的高性能访问方式。
 *
 * 主要功能：
 * - 定义统一的信号读写接口（支持不同数据宽度）
 * - 提供内存信号的索引访问支持
 * - 定义仿真控制接口（时钟推进、波形控制等）
 * - 支持仿真器特定的优化（如缓冲写入）
 *
 * 接口设计特点：
 * - 支持多种数据类型：Int、Long、BigInt
 * - 区分普通信号和内存信号访问
 * - 提供时间精度查询和控制
 * - 统一的资源管理和清理接口
 */

package spinal.sim

/**
 * SimRaw - 仿真原始接口抽象类
 *
 *
 * 在SpinalHDL架构中的位置：
 * 用户代码 -> SimManager -> SimRaw -> 仿真器后端(C++/VHDL)
 *
 * 这种设计允许：
 * - 支持多种仿真器后端
 * - 最大化性能
 * - 保持接口的简洁性
 * - 便于扩展新的仿真器支持
 */
abstract class SimRaw {
  var userData : Any = null                                           // 用户自定义数据，通常存储信号列表

  // 基本信号访问接口
  def getInt(signal : Signal) : Int                                   // 读取信号的整数值
  def getLong(signal : Signal) : Long                                 // 读取信号的长整数值
  def setLong(signal : Signal, value : Long) : Unit                   // 设置信号的长整数值
  def getBigInt(signal : Signal) : BigInt                             // 读取信号的大整数值
  def setBigInt(signal : Signal, value : BigInt): Unit                // 设置信号的大整数值

  // 内存信号访问接口（用于数组/内存类型信号）
  def getIntMem(signal : Signal, index : Long) : Int                  // 读取内存信号的整数值
  def getLongMem(signal : Signal, index : Long) : Long                // 读取内存信号的长整数值
  def setLongMem(signal : Signal, value : Long, index : Long): Unit   // 设置内存信号的长整数值
  def getBigIntMem(signal : Signal, index : Long) : BigInt            // 读取内存信号的大整数值
  def setBigIntMem(signal : Signal, value : BigInt, index : Long): Unit // 设置内存信号的大整数值

  // 仿真控制接口
  def getTimePrecision(): Int                                         // 获取时间精度 (10^x格式，如-9表示1ns)
  def sleep(cycles : Long): Unit                                      // 推进仿真指定周期数
  def enableWave(): Unit                                              // 启用波形记录
  def disableWave(): Unit                                             // 禁用波形记录
  def eval() : Boolean                                                // 评估仿真一个增量周期，返回是否有断言失败
  def end(): Unit                                                     // 结束仿真，清理资源
  def isBufferedWrite : Boolean                                       // 是否使用缓冲写入（性能优化）
}
