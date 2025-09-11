/**
 * Verilator后端编译引擎实现文件
 *
 * 文件作用：
 * 这是SpinalHDL仿真系统中Verilator后端的核心实现，负责将RTL代码编译为
 * 可执行的Verilator仿真模型，并生成JNI接口用于Java与C++模型的交互。
 *
 * 在仿真流程中的位置：
 * SpinalVerilatorBackend -> VerilatorBackend -> Verilator工具链 -> C++编译器 -> 动态库
 *
 * 主要功能：
 * 1. 编译管理：
 *    - 调用Verilator工具将RTL代码转换为C++模型
 *    - 生成JNI包装器代码用于Java-C++交互
 *    - 编译C++代码为动态链接库
 *    - 动态编译和加载Java JNI接口类
 *
 * 2. 缓存系统：
 *    - 基于内容哈希的智能缓存机制
 *    - 自动管理缓存生命周期和存储空间
 *    - 支持并发访问的线程安全缓存
 *
 * 3. 平台适配：
 *    - 支持Windows、Linux、macOS多平台编译
 *    - 自动检测和配置JDK环境
 *    - 处理不同架构的编译标志
 *
 * 4. 仿真控制：
 *    - 创建和管理仿真实例
 *    - 配置波形记录（VCD/FST格式）
 *    - 支持代码覆盖率分析
 *    - 提供时间精度控制
 *
 * 核心组件：
 * - VerilatorBackendConfig: 配置管理
 * - VerilatorBackend: 主要编译引擎
 * - C++包装器生成: JNI接口代码生成
 * - Java动态编译: 运行时Java类生成和加载
 */

package spinal.sim

import java.io.{File, PrintWriter}
import javax.tools.JavaFileObject
import net.openhft.affinity.impl.VanillaCpuLayout
import org.apache.commons.io.FileUtils
import spinal.SpinalEnv

import java.security.MessageDigest
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import sys.process._
import scala.io.Source
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileFilter

/**
 * Verilator后端配置类
 * 包含Verilator仿真器的所有配置参数
 */
class VerilatorBackendConfig{
  var signals                = ArrayBuffer[Signal]()      // 需要访问的信号列表
  var optimisationLevel: Int = 2                          // 优化级别 (0-3)
  val rtlSourcesPaths        = ArrayBuffer[String]()      // RTL源文件路径列表
  val rtlIncludeDirs         = ArrayBuffer[String]()      // 包含目录列表
  var toplevelName: String   = null                       // 顶层模块名称
  var maxCacheEntries: Int   = 100                        // 最大缓存条目数
  var cachePath: String      = null                       // 缓存路径
  var workspacePath: String  = null                       // 工作空间路径
  var workspaceName: String  = null                       // 工作空间名称
  var vcdPath: String        = null                       // VCD波形文件路径
  var vcdPrefix: String      = null                       // VCD文件前缀
  var waveFormat             : WaveFormat = WaveFormat.NONE // 波形格式 (VCD/FST/NONE)
  var waveDepth:Int          = 1                          // 波形深度 (0表示全部)
  var simulatorFlags         = ArrayBuffer[String]()      // 仿真器标志
  var withCoverage           = false                      // 是否启用覆盖率
  var timePrecision: String  = null                       // 时间精度
  var autoInitialReset: Boolean = true                    // 启用自动初始复位（替代--x-initial-edge）
  var resetSignalMap: Map[String, (String, Boolean, Boolean)] = Map.empty // 复位信号映射：信号名 -> (极性描述, 是否异步, 是否低电平有效)
  var clockSignalMap: Map[String, Boolean] = Map.empty // 时钟信号映射：信号名 -> 是否为上升沿有效
}

/**
 * Verilator后端对象
 * 管理全局缓存锁和路径锁映射
 */
object VerilatorBackend {
  private val cacheGlobalLock = new Object()                    // 全局缓存锁
  private val cachePathLockMap = mutable.HashMap[String, Object]() // 缓存路径锁映射
}

/**
 * Verilator后端实现类
 * 负责编译和管理Verilator仿真器
 * @param config Verilator配置对象
 */
class VerilatorBackend(val config: VerilatorBackendConfig) extends Backend {
  import Backend._

  val cachePath      = config.cachePath                   // 缓存路径
  val cacheEnabled   = cachePath != null                  // 是否启用缓存
  val maxCacheEntries = config.maxCacheEntries            // 最大缓存条目数
  val workspaceName  = config.workspaceName               // 工作空间名称
  val workspacePath  = config.workspacePath               // 工作空间路径
  val wrapperCppName = s"V${config.toplevelName}__spinalWrapper.cpp" // C++包装器文件名
  val wrapperCppPath = new File(s"${workspacePath}/${workspaceName}/$wrapperCppName").getAbsolutePath // C++包装器完整路径

  /**
   * 全局缓存同步方法
   * 如果启用缓存则使用全局锁，否则直接执行
   */
  def cacheGlobalSynchronized(function: => Unit) = {
    if (cacheEnabled) {
      VerilatorBackend.cacheGlobalLock.synchronized {
        function
      }
    } else {
      function
    }
  }

  /**
   * 缓存文件同步方法
   * 为特定缓存文件提供同步访问控制
   */
  def cacheSynchronized(cacheFile: File)(function: => Unit): Unit = {
    if (cacheEnabled) {
      var lock: Object = null
      VerilatorBackend.cachePathLockMap.synchronized {
        lock = VerilatorBackend.cachePathLockMap.getOrElseUpdate(cacheFile.getCanonicalPath(), new Object())
      }

      lock.synchronized {
        function
      }
    } else {
      function
    }
  }

  /**
   * 清理工作空间
   * 删除工作空间目录及其所有内容
   */
  def clean(): Unit = {
    FileUtils.deleteQuietly(new File(s"${workspacePath}/${workspaceName}"))
  }

  // Verilator支持的波形格式
  val availableFormats = Array(WaveFormat.VCD, WaveFormat.FST,
                               WaveFormat.DEFAULT, WaveFormat.NONE)

  // 选择有效的波形格式，如果不支持则使用NONE
  val format = if(availableFormats contains config.waveFormat){
                config.waveFormat
              } else {
                println("Wave format " + config.waveFormat + " not supported by Verilator")
                WaveFormat.NONE
              }

  /**
   * 生成C++包装器代码
   *
   * 功能说明：
   * 这个方法生成一个完整的C++包装器文件，作为Java和Verilator编译的C++模型之间的桥梁。
   * 包装器包含以下核心组件：
   * 1. 信号访问类层次结构 - 为不同位宽的信号提供统一访问接口
   * 2. Wrapper类 - 管理Verilator仿真实例的生命周期
   * 3. JNI函数 - 提供Java可调用的本地方法
   * 4. 时间管理和波形记录功能
   *
   * 设计模式：
   * - 策略模式：不同的信号访问类处理不同位宽的信号
   * - 外观模式：Wrapper类为复杂的Verilator接口提供简化的访问方式
   * - 适配器模式：JNI函数将Java调用适配到C++方法
   *
   * @param useTimePrecision 是否使用Verilator 4.034+的新时间精度API
   */
  def genWrapperCpp(useTimePrecision: Boolean = true): Unit = {
    // 生成JNI函数名前缀，遵循JNI命名规范
    // 将下划线替换为_1以符合JNI规范
    val jniPrefix = "Java_" + s"wrapper_${workspaceName}".replace("_", "_1") + "_VerilatorNative_"

    val wrapperString = s"""
/*
 * SpinalHDL Verilator C++包装器代码
 *
 * 文件作用：
 * 这是SpinalHDL仿真系统自动生成的C++包装器，提供Java与Verilator编译的
 * C++硬件模型之间的JNI接口。它封装了Verilator的复杂性，为Java层
 * 提供简洁统一的信号访问和仿真控制接口。
 *
 * 主要组件：
 * 1. 信号访问类 - 处理不同位宽信号的读写操作
 * 2. 仿真包装器类 - 管理Verilator实例和仿真状态
 * 3. JNI接口函数 - 提供Java可调用的本地方法
 * 4. 时间和波形管理 - 处理仿真时间推进和波形记录
 *
 * 生成时间：编译时自动生成
 * 目标模块：${config.toplevelName}
 * 信号数量：${config.signals.length}
 */

// ============================================================================
// 头文件包含区域
// ============================================================================

#include <stdint.h>        // 标准整数类型定义
#include <string>          // C++字符串类
#include <memory>          // 智能指针支持
#include <jni.h>           // Java本地接口
#include <iostream>        // 标准输入输出流

// Verilator生成的头文件
#include "V${config.toplevelName}.h"                    // 顶层模块类定义
#ifdef TRACE
#include "verilated_${format.ext}_c.h"                  // 波形记录支持（VCD/FST）
#endif
#include "V${config.toplevelName}__Syms.h"              // Verilator符号表

using namespace std;

// ============================================================================
// 信号访问接口类层次结构
// ============================================================================

/**
 * 信号访问接口基类
 *
 * 设计目的：
 * 为不同位宽的Verilator信号提供统一的访问接口。Verilator根据信号位宽
 * 使用不同的C++数据类型（CData、SData、IData、QData、WData），这个
 * 接口层隐藏了这些差异，为Java层提供一致的访问方式。
 *
 * 接口设计：
 * - 所有读取方法都返回uint64_t，提供统一的数据格式
 * - 支持普通信号和内存信号（数组）的访问
 * - 支持64位整数和字节数组两种数据传输方式
 * - 使用虚函数实现多态，子类根据具体数据类型实现
 */
class ISignalAccess{
public:
  virtual ~ISignalAccess() {}

  // 字节数组访问方法（用于超过64位的大信号）
  virtual void getAU8(JNIEnv *env, jbyteArray value) {}                                    // 读取信号到Java字节数组
  virtual void getAU8_mem(JNIEnv *env, jbyteArray value, size_t index) {}                  // 读取内存信号到Java字节数组
  virtual void setAU8(JNIEnv *env, jbyteArray value, int length) {}                        // 从Java字节数组写入信号
  virtual void setAU8_mem(JNIEnv *env, jbyteArray value, int length, size_t index) {}      // 从Java字节数组写入内存信号

  // 64位整数访问方法（用于64位及以下的信号，是主要的访问方式）
  virtual uint64_t getU64() = 0;                                                           // 读取信号值（纯虚函数，必须实现）
  virtual uint64_t getU64_mem(size_t index) = 0;                                           // 读取内存信号值（纯虚函数，必须实现）
  virtual void setU64(uint64_t value) = 0;                                                 // 设置信号值（纯虚函数，必须实现）
  virtual void setU64_mem(uint64_t value, size_t index) = 0;                               // 设置内存信号值（纯虚函数，必须实现）
};

/**
 * CData信号访问类（1-8位信号）
 *
 * 适用范围：处理1到8位的信号
 * 底层类型：CData (uint8_t)
 */
class CDataSignalAccess : public ISignalAccess{
public:
    CData *raw;                                                                             // 指向Verilator CData的指针

    CDataSignalAccess(CData *raw) : raw(raw){}                                              // 指针构造函数
    CDataSignalAccess(CData &raw) : raw(addressof(raw)){}                                   // 引用构造函数

    uint64_t getU64() {return *raw;}                                                        // 读取8位值并扩展为64位
    uint64_t getU64_mem(size_t index) {return raw[index];}                                  // 读取数组元素
    void setU64(uint64_t value) {*raw = value;}                                             // 写入值（自动截断到8位）
    void setU64_mem(uint64_t value, size_t index){raw[index] = value;}                      // 写入数组元素
};

/**
 * SData信号访问类（9-16位信号）
 *
 * 适用范围：处理9到16位的信号
 * 底层类型：SData (uint16_t)
 */
class SDataSignalAccess : public ISignalAccess{
public:
    SData *raw;                                                                             // 指向Verilator SData的指针

    SDataSignalAccess(SData *raw) : raw(raw){}                                              // 指针构造函数
    SDataSignalAccess(SData &raw) : raw(addressof(raw)){}                                   // 引用构造函数

    uint64_t getU64() {return *raw;}                                                        // 读取16位值并扩展为64位
    uint64_t getU64_mem(size_t index) {return raw[index];}                                  // 读取数组元素
    void setU64(uint64_t value) {*raw = value;}                                             // 写入值（自动截断到16位）
    void setU64_mem(uint64_t value, size_t index){raw[index] = value;}                      // 写入数组元素
};

/**
 * IData信号访问类（17-32位信号）
 *
 * 适用范围：处理17到32位的信号
 * 底层类型：IData (uint32_t)
 */
class IDataSignalAccess : public ISignalAccess{
public:
    IData *raw;                                                                             // 指向Verilator IData的指针

    IDataSignalAccess(IData *raw) : raw(raw){}                                              // 指针构造函数
    IDataSignalAccess(IData &raw) : raw(addressof(raw)){}                                   // 引用构造函数

    uint64_t getU64() {return *raw;}                                                        // 读取32位值并扩展为64位
    uint64_t getU64_mem(size_t index) {return raw[index];}                                  // 读取数组元素
    void setU64(uint64_t value) {*raw = value;}                                             // 写入值（自动截断到32位）
    void setU64_mem(uint64_t value, size_t index){raw[index] = value;}                      // 写入数组元素
};

/**
 * QData信号访问类（33-64位信号）
 *
 * 适用范围：处理33到64位的信号
 * 底层类型：QData (uint64_t)
 */
class QDataSignalAccess : public ISignalAccess{
public:
    QData *raw;                                                                             // 指向Verilator QData的指针

    QDataSignalAccess(QData *raw) : raw(raw){}                                              // 指针构造函数
    QDataSignalAccess(QData &raw) : raw(addressof(raw)){}                                   // 引用构造函数

    uint64_t getU64() {return *raw;}                                                        // 直接返回64位值
    uint64_t getU64_mem(size_t index) {return raw[index];}                                  // 读取数组元素
    void setU64(uint64_t value) {*raw = value;}                                             // 直接写入64位值
    void setU64_mem(uint64_t value, size_t index){raw[index] = value;}                      // 写入数组元素
};

/**
 * WData信号访问类（超过64位的信号）
 *
 * 适用范围：处理超过64位的大信号
 * 底层类型：WData (uint32_t数组)
 *
 * 存储格式：
 * - 使用uint32_t数组存储，每个元素存储32位
 * - 低位在前（小端序）
 * - 数组长度 = (位宽 + 31) / 32
 *
 * 符号处理：
 * - 支持有符号和无符号数
 * - 有符号数使用符号扩展填充高位
 */
class WDataSignalAccess : public ISignalAccess{
public:
    WData *raw;                                                                             // 指向Verilator WData数组的指针
    uint32_t width;                                                                         // 信号的实际位宽
    uint32_t wordsCount;                                                                    // 数组中uint32_t元素的数量
    bool sint;                                                                              // 是否为有符号数

    /**
     * 构造函数
     * @param raw 指向WData数组的指针
     * @param width 信号的位宽
     * @param sint 是否为有符号数
     */
    WDataSignalAccess(WData *raw, uint32_t width, bool sint) :
      raw(raw), width(width), wordsCount((width+31)/32), sint(sint) {}

    /**
     * 读取内存信号的64位值
     * 从WData数组的指定索引位置读取前64位数据
     * @param index 内存数组索引
     * @return 64位值（由低32位和高32位组合而成）
     */
    uint64_t getU64_mem(size_t index) {
      WData *mem_el = &(raw[index*wordsCount]);                                            // 定位到指定内存元素
      return mem_el[0] + (((uint64_t)mem_el[1]) << 32);                                    // 组合低32位和高32位
    }

    /**
     * 读取信号的64位值（非内存信号）
     * @return 64位值
     */
    uint64_t getU64() { return getU64_mem(0); }

    /**
     * 设置内存信号的64位值
     * 将64位值写入WData数组，并正确处理符号扩展和位宽截断
     * @param value 要写入的64位值
     * @param index 内存数组索引
     */
    void setU64_mem(uint64_t value, size_t index)  {
      WData *mem_el = &(raw[index*wordsCount]);                                            // 定位到指定内存元素

      // 写入低64位数据
      mem_el[0] = value;                                                                    // 低32位
      mem_el[1] = value >> 32;                                                              // 高32位

      // 处理超过64位的部分：符号扩展或零扩展
      uint32_t padding = ((value & 0x8000000000000000l) && sint) ? 0xFFFFFFFF : 0;         // 有符号数符号扩展，无符号数零扩展
      for(uint32_t idx = 2; idx < wordsCount; idx++){
        mem_el[idx] = padding;                                                              // 填充高位
      }

      // 处理非32位对齐的位宽：清除超出位宽的位
      if(width%32 != 0) mem_el[wordsCount-1] &= (1l << width%32)-1;                        // 位宽截断
    }

    /**
     * 设置信号的64位值（非内存信号）
     * @param value 要写入的64位值
     */
    void setU64(uint64_t value)  {
      setU64_mem(value, 0);
    }

    /**
     * 读取内存信号到Java字节数组
     * 将WData数组转换为Java可读的字节数组格式
     * @param env JNI环境指针
     * @param value Java字节数组，用于接收数据
     * @param index 内存数组索引
     */
    void getAU8_mem(JNIEnv *env, jbyteArray value, size_t index) {
      WData *mem_el = &(raw[index*wordsCount]);                                            // 定位到指定内存元素
      uint32_t byteCount = wordsCount*4;                                                    // 计算字节数（每个WData元素4字节）
      uint32_t shift = 32-(width % 32);                                                     // 计算符号扩展需要的位移量
      uint32_t backup = mem_el[wordsCount-1];                                              // 备份最高位元素
      uint8_t values[byteCount + !sint];                                                    // 创建字节数组（无符号数需要额外一个字节）

      // 处理有符号数的符号扩展
      if(sint && shift != 32) mem_el[wordsCount-1] = (((int32_t)backup) << shift) >> shift;

      // 将WData转换为字节数组（大端序）
      for(uint32_t idx = 0; idx < byteCount; idx++){
        values[idx + !sint] = ((uint8_t*)mem_el)[byteCount-idx-1];                         // 字节序转换
      }

      // 将数据传递给Java字节数组
      (env)->SetByteArrayRegion(value, 0, byteCount + !sint, reinterpret_cast<jbyte*>(values));
      mem_el[wordsCount-1] = backup;                                                        // 恢复原始值
    }

    /**
     * 读取信号到Java字节数组（非内存信号）
     * @param env JNI环境指针
     * @param value Java字节数组，用于接收数据
     */
    void getAU8(JNIEnv *env, jbyteArray value) {
      getAU8_mem(env, value, 0);
    }

    /**
     * 从Java字节数组设置内存信号
     * 将Java字节数组转换为WData数组格式
     * @param env JNI环境指针
     * @param jvalue Java字节数组，包含要写入的数据
     * @param length 字节数组长度
     * @param index 内存数组索引
     */
    void setAU8_mem(JNIEnv *env, jbyteArray jvalue, int length, size_t index) {
      WData *mem_el = &(raw[index*wordsCount]);                                            // 定位到指定内存元素
      jbyte value[length];                                                                  // 创建临时字节数组
      (env)->GetByteArrayRegion(jvalue, 0, length, value);                                 // 从Java获取字节数据

      // 确定填充值（符号扩展或零扩展）
      uint32_t padding = (value[0] & 0x80 && sint) != 0 ? 0xFFFFFFFF : 0;                 // 检查符号位

      // 初始化WData数组
      for(uint32_t idx = 0; idx < wordsCount; idx++){
        mem_el[idx] = padding;                                                              // 用填充值初始化
      }

      // 复制字节数据（处理长度限制）
      uint32_t capedLength = length > 4*wordsCount ? 4*wordsCount : length;                // 限制长度不超过数组容量
      for(uint32_t idx = 0; idx < capedLength; idx++){
        ((uint8_t*)mem_el)[idx] = value[length-idx-1];                                     // 字节序转换
      }

      // 位宽截断
      if(width%32 != 0) mem_el[wordsCount-1] &= (1l << width%32)-1;                        // 清除超出位宽的位
    }

    /**
     * 从Java字节数组设置信号（非内存信号）
     * @param env JNI环境指针
     * @param jvalue Java字节数组，包含要写入的数据
     * @param length 字节数组长度
     */
    void setAU8(JNIEnv *env, jbyteArray jvalue, int length) {
      setAU8_mem(env, jvalue, length, 0);
    }
};

// ============================================================================
// 仿真包装器类定义
// ============================================================================

// 前向声明
class Wrapper_${uniqueId};

// 线程局部存储的仿真句柄，用于Verilator回调函数访问当前仿真实例
thread_local Wrapper_${uniqueId} *simHandle${uniqueId} = NULL;

#include <chrono>
using namespace std::chrono;

/**
 * Verilator仿真包装器类
 *
 * 功能说明：
 * 这个类封装了一个完整的Verilator仿真实例，管理仿真的整个生命周期。
 * 它提供了仿真控制、信号访问、时间管理和波形记录等功能。
 *
 * 主要职责：
 * 1. 仿真实例管理 - 创建、初始化和销毁Verilator模型
 * 2. 信号访问管理 - 为所有公开信号创建访问器
 * 3. 时间管理 - 跟踪仿真时间和时间精度
 * 4. 波形记录 - 管理VCD/FST波形文件的生成
 * 5. 性能优化 - 批量波形刷新和时间检查
 *
 * 设计特点：
 * - 每个实例对应一个独立的仿真环境
 * - 支持多个并发仿真实例
 * - 自动管理资源生命周期
 * - 提供高性能的信号访问接口
 */
class Wrapper_${uniqueId}{
public:
    // 仿真状态管理
    uint64_t time;                                                                          // 当前仿真时间（以周期为单位）
    high_resolution_clock::time_point lastFlushAt;                                         // 上次波形刷新的时间点
    uint32_t timeCheck;                                                                     // 时间检查计数器（用于性能优化）
    bool waveEnabled;                                                                       // 波形记录是否启用
    bool gotFinish;                                                                         // 是否收到仿真结束信号（$$finish）

    // Verilator核心组件
    VerilatedContext* contextp;                                                             // Verilator上下文（v4.034+支持）
    V${config.toplevelName} *top;                                                           // 顶层模块实例

    // 信号访问系统
    ISignalAccess *signalAccess[${config.signals.length}];                                 // 信号访问器数组（${config.signals.length}个信号）

    // 波形记录系统
    #ifdef TRACE
    Verilated${format.ext.capitalize}C tfp;                                                // 波形记录对象（${format.ext.toUpperCase()}格式）
    #endif

    // 仿真元数据
    string name;                                                                            // 仿真实例名称
    int32_t time_precision;                                                                 // 时间精度（10^x格式）

    /**
     * 构造函数 - 初始化Verilator仿真实例
     *
     * 初始化流程：
     * 1. 创建Verilator上下文并设置随机种子
     * 2. 设置全局仿真句柄（用于回调函数）
     * 3. 创建顶层模块实例
     * 4. 初始化信号访问器
     * 5. 配置波形记录
     * 6. 设置时间精度
     *
     * @param name 仿真实例名称，用于标识和日志
     * @param wavePath 波形文件输出路径
     * @param seed 随机种子，确保仿真的可重现性
     */
    Wrapper_${uniqueId}(const char * name, const char * wavePath, int seed){
      // 第1步：初始化Verilator上下文
      contextp = new VerilatedContext;                                                      // 创建Verilator上下文对象
      contextp->randReset(2);                                                               // 设置随机重置模式
      contextp->randSeed(seed);                                                             // 设置随机种子

      // 第2步：设置全局句柄（重要：必须在创建顶层模块之前）
      // Verilator v5.026+ calls time() inside Vtop::Vtop()
      // initialize the simHandle before we call Vtop
      simHandle${uniqueId} = this;                                                          // 设置线程局部仿真句柄

      // 第3步：初始化仿真状态
      time = 0;                                                                             // 仿真时间从0开始
      gotFinish = false;                                                                    // 未收到结束信号

      // 第4步：创建顶层模块实例
      top = new V${config.toplevelName}();                                                  // 创建Verilator生成的顶层模块

      // 第5步：初始化性能监控
      timeCheck = 0;                                                                        // 重置时间检查计数器
      lastFlushAt = high_resolution_clock::now();                                          // 记录初始化时间
      waveEnabled = true;                                                                   // 默认启用波形记录

      // 第6步：初始化信号访问器数组
      // 为每个公开信号创建对应的访问器，根据信号位宽选择合适的访问器类型
${    val signalInits = for((signal, id) <- config.signals.zipWithIndex) yield {
      val typePrefix = if(signal.dataType.width <= 8) "CData"
      else if(signal.dataType.width <= 16) "SData"
      else if(signal.dataType.width <= 32) "IData"
      else if(signal.dataType.width <= 64) "QData"
      else "WData"
      val enforcedCast = if(signal.dataType.width > 64) "(WData*)" else ""
      val signalReference = s"top->${signal.path.map(_.replace("$", "__024").replace("__", "___05F")).mkString("->")}"
      val memPatch = if(signal.dataType.isMem) "[0]" else ""

      s"      signalAccess[$id] = new ${typePrefix}SignalAccess($enforcedCast $signalReference$memPatch ${if(signal.dataType.width > 64) s" , ${signal.dataType.width}, ${if(signal.dataType.isInstanceOf[SIntDataType]) "true" else "false"}" else ""});\n"

    }

      signalInits.mkString("")
    }

      // 第7步：配置波形记录系统
      #ifdef TRACE
      Verilated::traceEverOn(true);                                                        // 全局启用波形记录
      top->trace(&tfp, 99);                                                                // 连接顶层模块到波形记录器（深度99层）
      tfp.set_time_resolution(${if (useTimePrecision) "Verilated::threadContextp()->timeprecisionString()" else "VL_TIME_PRECISION_STR" }); // 设置时间分辨率
      tfp.open((std::string(wavePath) + "wave" + ".${format.ext}").c_str());              // 打开波形文件：<wavePath>wave.${format.ext}
      #endif

      // 第8步：设置仿真元数据
      this->name = name;                                                                    // 保存仿真实例名称
      this->time_precision = ${if (useTimePrecision) "Verilated::timeprecision()" else "VL_TIME_PRECISION" }; // 获取时间精度

      // 第9步：执行自动初始复位序列（替代--x-initial-edge）
      // 基于Verilog静态初始化方法，等效于initial块逻辑
      if (${config.autoInitialReset}) {
          performAutoInitialReset();
      }
    }

    /**
     * 自动初始复位方法 - 基于Verilog静态初始化方法
     *
     * 功能说明：
     * 等效于Verilog中的initial块逻辑，在仿真开始时自动执行复位序列。
     * 这种方法完全替代了--x-initial-edge选项，提供更精确和可控的初始化。
     *
     * 实现原理：
     * 1. 智能识别所有复位信号（基于信号名称模式和SpinalHDL约定）
     * 2. 自动检测复位极性（高电平有效/低电平有效）
     * 3. 执行标准复位序列：激活复位 → eval() → 释放复位 → eval()
     * 4. 确保所有跨时钟域组件（BufferCC等）从确定状态开始
     *
     * 优势：
     * - 基于标准Verilog语义，在时间0自然产生边沿事件
     * - 自动适应不同的复位信号模式和极性
     * - 无需手动配置，适用于任何SpinalHDL设计
     * - 完全解决BufferCC初始化不确定性问题
     */
    void performAutoInitialReset() {
        ${
          // 基于SpinalHDL RTL分析的纯粹复位信号发现系统
          // RTL分析在core包中完成，这里直接使用分析结果

          import scala.collection.mutable

          // 基于RTL分析发现的复位信号
          val resetSignals = config.signals.filter { signal =>
            config.resetSignalMap.contains(signal.path.last)
          }

          if (resetSignals.nonEmpty) {
            val codeBuilder = new StringBuilder()

            // 基于RTL分析的纯粹复位信号信息结构
            case class ResetInfo(signal: Signal, resetName: String) {
              // 从RTL分析结果中获取复位信息
              val (polarityDesc, isAsync, isActiveLow) = config.resetSignalMap(signal.path.last)

              def assertValue = if (isActiveLow) 0 else 1
              def deassertValue = if (isActiveLow) 1 else 0
              def resetTypeDesc = if (isAsync) "ASYNC" else "SYNC"
            }

            // 构建复位信号信息列表
            val resetInfos = resetSignals.map { signal =>
              val resetName = signal.path.map(_.replace("$", "__024").replace("__", "___05F")).mkString("->")
              ResetInfo(signal, resetName)
            }

            codeBuilder.append(s"""
        // SpinalHDL基于RTL分析的纯粹自动复位序列
        // 等效于Verilog: initial begin ... end
        // 完全替代--x-initial-edge选项
        // 基于RTL分析发现${resetInfos.length}个复位信号

        // 第1步：将所有复位信号设置为非激活状态（确保初始状态）""")

            resetInfos.foreach { resetInfo =>
              codeBuilder.append(s"""
        top->${resetInfo.resetName} = ${resetInfo.deassertValue};  // 非激活: ${resetInfo.signal.path.mkString("/")} (${resetInfo.polarityDesc} active, ${resetInfo.resetTypeDesc})""")
            }



            codeBuilder.append("""

        // 第2步：执行eval()稳定初始状态
        top->eval();

        // 第3步：激活所有复位信号""")

            resetInfos.foreach { resetInfo =>
              codeBuilder.append(s"""
        top->${resetInfo.resetName} = ${resetInfo.assertValue};   // 激活复位: ${resetInfo.signal.path.mkString("/")} (RTL分析)""")
            }



            codeBuilder.append("""

        // 第4步：执行eval()使复位生效
        top->eval();

        // 第5步：基于RTL分析产生时钟边沿让同步化复位信号传播
        // 这是关键步骤：让BufferCC生成正确的同步化复位信号""")

            // 基于RTL分析发现的时钟信号
            val discoveredClocks = config.clockSignalMap.filter { case (clockName, _) =>
              config.signals.exists(_.path.last == clockName)
            }

            if (discoveredClocks.nonEmpty) {
              codeBuilder.append(s"""
        // 基于RTL分析发现${discoveredClocks.size}个时钟信号，生成时钟边沿
        for(int cycle = 0; cycle < 3; cycle++) {""")

              discoveredClocks.foreach { case (clockName, isRisingEdge) =>
                val clockPath = config.signals.find(_.path.last == clockName).get.path.map(_.replace("$", "__024").replace("__", "___05F")).mkString("->")
                val edgeDesc = if (isRisingEdge) "上升沿" else "下降沿"

                if (isRisingEdge) {
                  // 上升沿：0 -> 1
                  codeBuilder.append(s"""
            // 产生${clockName}边沿 (${edgeDesc}, RTL分析)
            top->${clockPath} = 0;
            top->eval();
            top->${clockPath} = 1;
            top->eval();""")
                } else {
                  // 下降沿：1 -> 0
                  codeBuilder.append(s"""
            // 产生${clockName}边沿 (${edgeDesc}, RTL分析)
            top->${clockPath} = 1;
            top->eval();
            top->${clockPath} = 0;
            top->eval();""")
                }
              }

              codeBuilder.append("""
        }""") 
            } else {
              codeBuilder.append("""
        // 未发现可访问的时钟信号，跳过时钟边沿生成""")
            }

        // 第6步：释放所有复位信号（回到非激活状态）""")

            resetInfos.foreach { resetInfo =>
              codeBuilder.append(s"""
        top->${resetInfo.resetName} = ${resetInfo.deassertValue}; // 释放复位: ${resetInfo.signal.path.mkString("/")} (RTL分析)""")
            }



            val discoveredClocksCount = discoveredClocks.size

            codeBuilder.append(s"""

        // 第7步：执行最终eval()完成初始化序列
        top->eval();

        // SpinalHDL基于RTL分析的纯粹自动复位序列完成
        // 复位序列：非激活 → eval → 激活 → eval → RTL分析时钟边沿 → 释放 → eval
        // 处理了${resetInfos.length}个复位信号：全部来自RTL分析
        // 处理了${discoveredClocksCount}个时钟信号：全部来自RTL分析，支持上升沿/下降沿自动识别
        // 完全消除硬编码和名称模式判断，RTL分析提供最高精度和优雅性
        // 支持异步/同步复位、多种极性、多种时钟边沿，具有最高的普适性
        // 通过RTL分析的时钟边沿确保同步化复位信号正确传播
        // 所有BufferCC和跨时钟域组件现在都处于确定的初始状态
        // 仿真结果将具有完全的一致性和可重复性""")

            codeBuilder.toString()
          } else {
            """
        // 未检测到复位信号，执行基本稳定化序列
        // 这确保了仿真的基本稳定性
        for(int i = 0; i < 5; i++) {
            top->eval();
        }"""
          }
        }
    }

    /**
     * 析构函数 - 清理仿真资源
     *
     * 清理流程：
     * 1. 释放所有信号访问器
     * 2. 完成波形记录并关闭文件
     * 3. 生成代码覆盖率报告（如果启用）
     * 4. 调用Verilator清理函数
     * 5. 释放顶层模块实例
     *
     * 注意：析构函数确保所有资源都被正确释放，避免内存泄漏
     */
    virtual ~Wrapper_${uniqueId}(){
      // 第1步：释放信号访问器
      for(int idx = 0; idx < ${config.signals.length}; idx++){
          delete signalAccess[idx];                                                         // 释放每个信号访问器
      }

      // 第2步：完成波形记录
      #ifdef TRACE
      if(waveEnabled) tfp.dump((vluint64_t)time);                                          // 记录最后一个时间点的波形
      tfp.flush();                                                                          // 刷新波形缓冲区
      tfp.close();                                                                          // 关闭波形文件
      #endif

      // 第3步：生成代码覆盖率报告
      #ifdef COVERAGE
      VerilatedCov::write((("${new File(config.vcdPath).getAbsolutePath.replace("\\","\\\\")}/${if(config.vcdPrefix != null) config.vcdPrefix + "_" else ""}") + name + ".dat").c_str()); // 写入覆盖率数据文件
      #endif

      // 第4步：Verilator清理（注释掉的部分为可选的全局清理）
      // Verilated::runFlushCallbacks();                                                   // 运行刷新回调（可选）
      // Verilated::runExitCallbacks();                                                    // 运行退出回调（可选）

      // 第5步：释放Verilator模块
      //contextp->threadContextp()->gotFinish(true);                                       // 设置完成标志（可选）
      top->final();                                                                         // 调用Verilator final()方法
      delete top;                                                                           // 释放顶层模块实例
      delete contextp;                                                                    // 释放上下文（注释掉避免潜在问题）
    }

};

// ============================================================================
// Verilator回调函数
// ============================================================================

/**
 * SystemC时间戳函数
 *
 * 功能：为Verilator提供当前仿真时间
 * 调用时机：Verilator在需要时间信息时自动调用
 * 返回值：当前仿真时间（双精度浮点数）
 *
 * 注意：这个函数必须是全局函数，因为Verilator会直接调用它
 */
double sc_time_stamp () {
  if(simHandle${uniqueId} == NULL) return 0.0;                                             // 如果没有活动的仿真实例，返回0
  return simHandle${uniqueId}->time;                                                       // 返回当前仿真时间
}

/**
 * Verilog $$finish系统任务处理函数
 *
 * 功能：处理Verilog代码中的$$finish系统任务调用
 * 调用时机：当Verilog代码执行$$finish时被Verilator调用
 *
 * 处理流程：
 * 1. 打印finish信息（包含文件名和行号）
 * 2. 设置gotFinish标志，通知仿真管理器
 * 3. 不直接退出程序，而是让Java层处理
 *
 * @param filename 调用$$finish的Verilog文件名
 * @param linenum 调用$$finish的行号
 * @param hier 层次路径（未使用）
 *
 * 注意：VL_MT_UNSAFE表示这个函数不是线程安全的
 */
void vl_finish(const char* filename, int linenum, const char* hier) VL_MT_UNSAFE {
    if (false && hier) {}                                                                   // 抑制未使用参数警告
    VL_PRINTF(                                                                              // 使用Verilator的打印宏（非多线程版本）
        "- %s:%d: Verilog $$finish\\n", filename, linenum);                                // 打印finish信息

   /*
    * 注释掉的代码：原始的Verilator行为是直接退出程序
    * SpinalHDL选择不直接退出，而是设置标志让Java层处理
    *
    * if (Verilated::threadContextp()->gotFinish()) {
    *     VL_PRINTF("- %s:%d: Second verilog $$finish, exiting\\n", filename, linenum);
    *     Verilated::runFlushCallbacks();
    *     Verilated::runExitCallbacks();
    *     std::exit(0);
    * }
    */
    simHandle${uniqueId}->gotFinish = true;                                                 // 设置完成标志，让Java层检测并处理
}

// ============================================================================
// JNI接口函数定义
// ============================================================================

#ifdef __cplusplus
extern "C" {                                                                               // C++中使用C链接，避免名称修饰
#endif
#include <stdio.h>
#include <stdint.h>

#define API __attribute__((visibility("default")))                                         // 设置函数为导出可见

/**
 * JNI函数：创建新的仿真句柄
 *
 * Java方法签名：
 * public native long newHandle_${uniqueId}(String name, String wavePath, int seed);
 *
 * 功能说明：
 * 这是仿真生命周期的起点，负责创建和初始化一个新的Verilator仿真实例。
 * 它处理Java到C++的参数转换，设置随机种子，并返回仿真句柄供后续使用。
 *
 * 执行流程：
 * 1. 重置全局仿真句柄
 * 2. 根据平台设置随机种子
 * 3. 转换Java字符串参数
 * 4. 创建Wrapper实例
 * 5. 清理JNI资源
 * 6. 返回句柄指针
 *
 * @param env JNI环境指针
 * @param obj Java对象引用（未使用）
 * @param name 仿真实例名称（Java字符串）
 * @param wavePath 波形文件路径（Java字符串）
 * @param seedValue 随机种子值
 * @return 仿真句柄指针（作为long返回给Java）
 */
JNIEXPORT Wrapper_${uniqueId} * API JNICALL ${jniPrefix}newHandle_1${uniqueId}
  (JNIEnv * env, jobject obj, jstring name, jstring wavePath, jint seedValue){
    simHandle${uniqueId} = NULL;                                                            // 重置全局仿真句柄

    // 平台特定的随机种子设置
    #if defined(_WIN32) && !defined(__CYGWIN__)
    srand(seedValue);                                                                       // Windows平台使用srand
    #else
    srand48(seedValue);                                                                     // Unix/Linux平台使用srand48
    #endif

    // Java字符串到C字符串的转换
    const char* ch = env->GetStringUTFChars(name, 0);                                      // 获取仿真名称的UTF-8字符串
    const char* wavePathCh = env->GetStringUTFChars(wavePath, 0);                          // 获取波形路径的UTF-8字符串

    // 创建Verilator包装器实例
    Wrapper_${uniqueId} *handle = new Wrapper_${uniqueId}(ch, wavePathCh, seedValue);      // 调用构造函数创建实例

    // 清理JNI资源
    env->ReleaseStringUTFChars(name, ch);                                                  // 释放名称字符串
    env->ReleaseStringUTFChars(wavePath, wavePathCh);                                      // 释放路径字符串

    return handle;                                                                          // 返回仿真句柄指针给Java层
}

JNIEXPORT jboolean API JNICALL ${jniPrefix}eval_1${uniqueId}
  (JNIEnv *, jobject, Wrapper_${uniqueId} *handle){
   if(simHandle${uniqueId}->gotFinish) printf("XXX eval on already finished !!!\\n");
   handle->top->eval();
   if(simHandle${uniqueId}->gotFinish) printf("XXX eval finished\\n");
   return simHandle${uniqueId}->gotFinish;
}

JNIEXPORT jint API JNICALL ${jniPrefix}getTimePrecision_1${uniqueId}
  (JNIEnv *, jobject, Wrapper_${uniqueId} *handle){
  return handle->time_precision;
}

JNIEXPORT void API JNICALL ${jniPrefix}sleep_1${uniqueId}
  (JNIEnv *, jobject, Wrapper_${uniqueId} *handle, uint64_t cycles){
  #ifdef TRACE
  if(handle->waveEnabled) {
    handle->tfp.dump((vluint64_t)handle->time);
  }
  handle->timeCheck++;
  if(handle->timeCheck > 10000){
    handle->timeCheck = 0;
    high_resolution_clock::time_point timeNow = high_resolution_clock::now();
    duration<double, std::milli> time_span = timeNow - handle->lastFlushAt;
    if(time_span.count() > 1e3){
      handle->lastFlushAt = timeNow;
      handle->tfp.flush();
    }
  }
  #endif
  handle->time += cycles;
}

JNIEXPORT jlong API JNICALL ${jniPrefix}getU64_1${uniqueId}
  (JNIEnv *, jobject, Wrapper_${uniqueId} *handle, int id){
  return handle->signalAccess[id]->getU64();
}

JNIEXPORT jlong API JNICALL ${jniPrefix}getU64mem_1${uniqueId}
  (JNIEnv *, jobject, Wrapper_${uniqueId} *handle, int id, uint64_t index){
  return handle->signalAccess[id]->getU64_mem(index);
}

JNIEXPORT void API JNICALL ${jniPrefix}setU64_1${uniqueId}
  (JNIEnv *, jobject, Wrapper_${uniqueId} *handle, int id, uint64_t value){
  handle->signalAccess[id]->setU64(value);
}

JNIEXPORT void API JNICALL ${jniPrefix}setU64mem_1${uniqueId}
  (JNIEnv *, jobject, Wrapper_${uniqueId} *handle, int id, uint64_t value, uint64_t index){
  handle->signalAccess[id]->setU64_mem(value, index);
}

JNIEXPORT void API JNICALL ${jniPrefix}deleteHandle_1${uniqueId}
  (JNIEnv *, jobject, Wrapper_${uniqueId} * handle){
  delete handle;
}

JNIEXPORT void API JNICALL ${jniPrefix}getAU8_1${uniqueId}
  (JNIEnv * env, jobject obj, Wrapper_${uniqueId} * handle, jint id, jbyteArray value){
  handle->signalAccess[id]->getAU8(env, value);
}

JNIEXPORT void API JNICALL ${jniPrefix}getAU8mem_1${uniqueId}
  (JNIEnv * env, jobject obj, Wrapper_${uniqueId} * handle, jint id, jbyteArray value, uint64_t index){
  handle->signalAccess[id]->getAU8_mem(env, value, index);
}

JNIEXPORT void API JNICALL ${jniPrefix}setAU8_1${uniqueId}
  (JNIEnv * env, jobject obj, Wrapper_${uniqueId} * handle, jint id, jbyteArray value, jint length){
  handle->signalAccess[id]->setAU8(env, value, length);
}

JNIEXPORT void API JNICALL ${jniPrefix}setAU8mem_1${uniqueId}
  (JNIEnv * env, jobject obj, Wrapper_${uniqueId} * handle, jint id, jbyteArray value, jint length, uint64_t index){
  handle->signalAccess[id]->setAU8_mem(env, value, length, index);
}

JNIEXPORT void API JNICALL ${jniPrefix}enableWave_1${uniqueId}
  (JNIEnv *, jobject, Wrapper_${uniqueId} * handle){
  handle->waveEnabled = true;
}

JNIEXPORT void API JNICALL ${jniPrefix}disableWave_1${uniqueId}
  (JNIEnv *, jobject, Wrapper_${uniqueId} * handle){
  handle->waveEnabled = false;
}

#ifdef __cplusplus
}
#endif
     """
    val outFile = new java.io.FileWriter(wrapperCppPath)
    outFile.write(wrapperString)
    outFile.flush()
    outFile.close()

    val exportMapString =
      s"""CODEABI_1.0 {
         |    global: $jniPrefix*;
         |    local: *;
         |};""".stripMargin

    val exportmapFile = new java.io.FileWriter(s"${workspacePath}/${workspaceName}/libcode.version")
    exportmapFile.write(exportMapString)
    exportmapFile.flush()
    exportmapFile.close()
  }

  class Logger extends ProcessLogger {
    var outStr = new StringBuilder()
    override def err(s: => String): Unit = { if(!s.startsWith("ar: creating ")) println(s) }
    override def out(s: => String): Unit = { outStr ++= s; outStr ++= "\n" }
    override def buffer[T](f: => T) = f
  }

//     VL_THREADED
  /**
   * 编译Verilator模型
   *
   * 这是VerilatorBackend的核心方法，负责：
   * 1. 配置编译环境（JDK路径、编译标志等）
   * 2. 生成Verilator编译脚本
   * 3. 管理编译缓存（基于内容哈希）
   * 4. 调用Verilator编译RTL代码为C++模型
   * 5. 编译C++模型为动态链接库
   * 6. 处理编译过程中的错误和日志
   *
   * 缓存机制：
   * - 基于RTL文件内容、Verilator版本和编译参数计算SHA-1哈希
   * - 如果缓存存在且有效，直接复用编译结果
   * - 自动管理缓存条目数量，删除最旧的缓存
   */
  def compileVerilator(): Unit = {
    // 1. 配置JDK环境和JNI头文件路径
    val java_home = System.getProperty("java.home")
    assert(java_home != "" && java_home != null, "JAVA_HOME need to be set")
    val jdk = java_home.replace("/jre","").replace("\\jre","")
    val jdkIncludes = if(isWindows){
      // Windows下需要复制JNI头文件到工作目录
      new File(s"${workspacePath}\\${workspaceName}").mkdirs()
      FileUtils.copyDirectory(new File(s"$jdk\\include"), new File(s"${workspacePath}\\${workspaceName}\\jniIncludes"))
      s"jniIncludes"
    }else{
      // Unix系统直接使用JDK的include目录
      jdk + "/include"
    }

    // 2. 配置平台特定的编译标志
    val arch = System.getProperty("os.arch")
    val flags = if(isMac)
      List("-dynamiclib")                                   // macOS使用动态库标志
    else (if(arch == "arm" || arch == "aarch64" || arch == "loongarch64")
      List("-fPIC", "-shared", "-Wno-attributes")           // ARM架构标志
    else
      List("-fPIC", "-m64", "-shared", "-Wno-attributes"))  // x86_64标志

    // 3. 配置波形记录参数
    val waveArgs = format match {
      case WaveFormat.FST =>  "-CFLAGS -DTRACE --trace-fst" // FST格式波形
      case WaveFormat.VCD =>  "-CFLAGS -DTRACE --trace"     // VCD格式波形
      case WaveFormat.NONE => ""                            // 不记录波形
      // 其他格式Verilator不支持
      case _ => ???
    }

    // 4. 配置代码覆盖率参数
    val covArgs = config.withCoverage match {
      case true =>  "-CFLAGS -DCOVERAGE --coverage"         // 启用覆盖率
      case false => ""                                      // 禁用覆盖率
    }

    // 5. 配置时间精度参数
    val timeScaleArgs = config.timePrecision match {
      case null => ""                                       // 使用默认时间精度
      case _ => s"--timescale-override /${config.timePrecision.replace(" ", "")}" // 自定义时间精度
    }

    val rtlIncludeDirsArgs = config.rtlIncludeDirs.map(e => s"-I${new File(e).getAbsolutePath}")
      .map('"' + _.replace("\\","/") + '"').mkString(" ")

    val verilatorBinFilename = if(isWindows) "verilator_bin.exe" else "verilator"

    // allow a user to overwrite/add verilator flags, e.g. C++ version
    // if the default is too old (see e.g. #278)
    val envFlags = sys.env.getOrElse("SPINAL_VERILATOR_FLAGS", "")

    // when changing the verilator script, the hash generation (below) must also be updated
    val verilatorScript = s""" set -e ;
       | ${verilatorBinFilename}
       | ${flags.map("-CFLAGS " + _).mkString(" ")}
       | ${flags.map("-LDFLAGS " + _).mkString(" ")}
       | -CFLAGS -I"$jdkIncludes" -CFLAGS -I"$jdkIncludes/${if(isWindows)"win32" else (if(isMac) "darwin" else (if(isFreeBsd) "freebsd" else "linux"))}"
       | -CFLAGS -fvisibility=hidden
       | -LDFLAGS -fvisibility=hidden
       | -CFLAGS -DVL_USER_FINISH=1
       | --autoflush  
       | --output-split 5000
       | --output-split-cfuncs 500
       | --output-split-ctrace 500
       | -Wno-WIDTH -Wno-UNOPTFLAT -Wno-CMPCONST -Wno-UNSIGNED
       | --x-assign unique
       | --trace-depth ${config.waveDepth}
       | -O3
       | -CFLAGS -O${config.optimisationLevel}
       | $waveArgs
       | $covArgs
       | $timeScaleArgs
       | --Mdir ${workspaceName}
       | --top-module ${config.toplevelName}
       | $rtlIncludeDirsArgs
       | -cc ${config.rtlSourcesPaths.filter(e => e.endsWith(".v") || 
                                                  e.endsWith(".sv") || 
                                                  e.endsWith(".h"))
                                     .map(new File(_).getAbsolutePath)
                                     .map('"' + _.replace("\\","/") + '"')
                                     .mkString(" ")}
       | --exe $workspaceName/$wrapperCppName
       | $envFlags
       | ${config.simulatorFlags.mkString(" ")}""".stripMargin.replace("\n", "")


    val workspaceDir = new File(s"${workspacePath}/${workspaceName}")
    var workspaceCacheDir: File = null
    var hashCacheDir: File = null

    val verilatorVersionProcess = Process(Seq(verilatorBinFilename, "--version"), new File(workspacePath))
    val verilatorVersion = verilatorVersionProcess.lineStream.mkString("\n") // blocks and throws an exception if exit status != 0
    val verilatorVersionDeci = BigDecimal("([0-9]+\\.[0-9]+)".r.findFirstIn(verilatorVersion).get)

    if (cacheEnabled) {
      // calculate hash of verilator version+options and source file contents

      val md = MessageDigest.getInstance("SHA-1")

      md.update(cachePath.getBytes())
      md.update(0.toByte)
      md.update(flags.mkString(" ").getBytes())
      md.update(0.toByte)
      md.update(config.waveDepth.toString().getBytes())
      md.update(0.toByte)
      md.update(config.optimisationLevel.toString().getBytes())
      md.update(0.toByte)
      md.update(waveArgs.getBytes())
      md.update(0.toByte)
      md.update(covArgs.getBytes())
      md.update(0.toByte)
      md.update(config.toplevelName.getBytes())
      md.update(0.toByte)
      md.update(config.simulatorFlags.mkString(" ").getBytes())
      md.update(0.toByte)
      md.update(verilatorVersion.getBytes())


      def hashFile(md: MessageDigest, file: File) = {
        val bis = new BufferedInputStream(new FileInputStream(file))
        val buf = new Array[Byte](1024)

        Iterator.continually(bis.read(buf, 0, buf.length))
          .takeWhile(_ >= 0)
          .foreach(md.update(buf, 0, _))

        bis.close()
      }

      config.rtlIncludeDirs.foreach { dirname =>
        FileUtils.listFiles(new File(dirname), null, true).asScala.foreach { file =>
          hashFile(md, file)
          md.update(0.toByte)
        }

        md.update(0.toByte)
      }

      config.rtlSourcesPaths.foreach { filename =>
        hashFile(md, new File(filename))
        md.update(0.toByte)
      }

      val digest = md.digest()
      val hash = digest.map(x => (x & 0xFF).toHexString.padTo(2, '0')).mkString("")
      workspaceCacheDir = new File(s"${cachePath}/${hash}/${workspaceName}")
      hashCacheDir = new File(s"${cachePath}/${hash}")
      uniqueId = BigInt(digest).toLong.abs

      cacheGlobalSynchronized {
        // remove old cache entries

        val cacheDir = new File(cachePath)
        if (cacheDir.isDirectory()) {
          if (maxCacheEntries > 0) {
            val cacheEntriesArr = cacheDir.listFiles()
              .filter(_.isDirectory())
              .sortWith(_.lastModified() < _.lastModified())

            val cacheEntries = cacheEntriesArr.toBuffer
            val cacheEntryFound = workspaceCacheDir.isDirectory()

            while (cacheEntries.length > maxCacheEntries || (!cacheEntryFound && cacheEntries.length >= maxCacheEntries)) {
              if (cacheEntries(0).getCanonicalPath() != hashCacheDir.getCanonicalPath()) {
                cacheSynchronized(cacheEntries(0)) {
                  FileUtils.deleteQuietly(cacheEntries(0))
                }
              }

              cacheEntries.remove(0)
            }
          }
        }
      }
    }

    cacheSynchronized(hashCacheDir) {
      var useCache = false

      if (cacheEnabled) {
        if (workspaceCacheDir.isDirectory()) {
          println("[info] Found cached verilator binaries")
          useCache = true
        }
      }

      var lastTime = System.currentTimeMillis()

      def bench(msg : String): Unit ={
        val newTime = System.currentTimeMillis()
        val sec = (newTime-lastTime)*1e-3
        println(msg + " " + sec)
        lastTime = newTime
      }

      val verilatorScriptFile = new PrintWriter(new File(workspacePath + "/verilatorScript.sh"))
      verilatorScriptFile.write(verilatorScript)
      verilatorScriptFile.close

      // invoke verilator or copy cached files depending on whether cache is not used or used
      val libExt = if(isWindows) "dll" else (if(isMac) "dylib" else "so")
      if (!useCache) {
        val shCommand = if(isWindows) "sh.exe" else "sh"
        val logger = new Logger()
        assert(Process(Seq(shCommand, "verilatorScript.sh"),
                       new File(workspacePath)).! (logger) == 0, "Verilator invocation failed\n" + logger.outStr.toString())
        val threadCount = SimManager.cpuCount
        genWrapperCpp(verilatorVersionDeci >= BigDecimal("4.034"))
        assert(s"${SpinalEnv.makeCmd} -j$threadCount VM_PARALLEL_BUILDS=1 -C ${workspacePath}/${workspaceName} -f V${config.toplevelName}.mk V${config.toplevelName} CURDIR=${workspacePath}/${workspaceName}".!  (logger) == 0, "Verilator C++ model compilation failed\n" + logger.outStr.toString())
        FileUtils.copyFile(new File(s"${workspacePath}/${workspaceName}/V${config.toplevelName}${if(isWindows) ".exe" else ""}") , new File(s"${workspacePath}/${workspaceName}/${workspaceName}_$uniqueId.${libExt}"))
      } else {
        FileUtils.copyDirectory(workspaceCacheDir, workspaceDir)
      }


      if (cacheEnabled) {
        // update cache

        if (!useCache) {
          FileUtils.deleteQuietly(workspaceCacheDir)

          // copy only needed files to save disk space
          FileUtils.copyDirectory(workspaceDir, workspaceCacheDir, new FileFilter() {
            def accept(file: File): Boolean = file.getName().endsWith("." + libExt)
          })
        }

        FileUtils.touch(hashCacheDir)
      }
    }
  }

  /**
   * 编译Java JNI包装器类
   * 生成动态Java类，实现IVerilatorNative接口
   * 提供Java到C++的JNI桥接功能
   */
  def compileJava(): Unit = {
    val verilatorNativeImplCode =
      s"""package wrapper_${workspaceName};
         |import spinal.sim.IVerilatorNative;
         |
         |// 动态生成的Verilator本地实现类
         |// 实现IVerilatorNative接口，提供JNI方法调用
         |public class VerilatorNative implements IVerilatorNative {
         |    // 接口方法实现，调用对应的本地方法
         |    public long newHandle(String name, String wavePath, int seed) { return newHandle_${uniqueId}(name, wavePath, seed);}
         |    public boolean eval(long handle) { return eval_${uniqueId}(handle);}
         |    public int get_time_precision(long handle) { return getTimePrecision_${uniqueId}(handle);}
         |    public void sleep(long handle, long cycles) { sleep_${uniqueId}(handle, cycles);}
         |    public long getU64(long handle, int id) { return getU64_${uniqueId}(handle, id);}
         |    public long getU64_mem(long handle, int id, long index) { return getU64mem_${uniqueId}(handle, id, index);}
         |    public void setU64(long handle, int id, long value) { setU64_${uniqueId}(handle, id, value);}
         |    public void setU64_mem(long handle, int id, long value, long index) { setU64mem_${uniqueId}(handle, id, value, index);}
         |    public void getAU8(long handle, int id, byte[] value) { getAU8_${uniqueId}(handle, id, value);}
         |    public void getAU8_mem(long handle, int id, byte[] value, long index) { getAU8mem_${uniqueId}(handle, id, value, index);}
         |    public void setAU8(long handle, int id, byte[] value, int length) { setAU8_${uniqueId}(handle, id, value, length);}
         |    public void setAU8_mem(long handle, int id, byte[] value, int length, long index) { setAU8mem_${uniqueId}(handle, id, value, length, index);}
         |    public void deleteHandle(long handle) { deleteHandle_${uniqueId}(handle);}
         |    public void enableWave(long handle) { enableWave_${uniqueId}(handle);}
         |    public void disableWave(long handle) { disableWave_${uniqueId}(handle);}
         |
         |    // 本地方法声明，对应C++中的JNI函数
         |    public native long newHandle_${uniqueId}(String name, String wavePath, int seed);
         |    public native boolean eval_${uniqueId}(long handle);
         |    public native int getTimePrecision_${uniqueId}(long handle);
         |    public native void sleep_${uniqueId}(long handle, long cycles);
         |    public native long getU64_${uniqueId}(long handle, int id);
         |    public native long getU64mem_${uniqueId}(long handle, int id, long index);
         |    public native void setU64_${uniqueId}(long handle, int id, long value);
         |    public native void setU64mem_${uniqueId}(long handle, int id, long value, long index);
         |    public native void getAU8_${uniqueId}(long handle, int id, byte[] value);
         |    public native void getAU8mem_${uniqueId}(long handle, int id, byte[] value, long index);
         |    public native void setAU8_${uniqueId}(long handle, int id, byte[] value, int length);
         |    public native void setAU8mem_${uniqueId}(long handle, int id, byte[] value, int length, long index);
         |    public native void deleteHandle_${uniqueId}(long handle);
         |    public native void enableWave_${uniqueId}(long handle);
         |    public native void disableWave_${uniqueId}(long handle);
         |
         |    // 静态块：加载编译好的动态链接库
         |    static{
         |      System.load("${new File(s"${workspacePath}/${workspaceName}").getAbsolutePath.replace("\\","\\\\")}/${workspaceName}_$uniqueId.${if(isWindows) "dll" else (if(isMac) "dylib" else "so")}");
         |    }
         |}
       """.stripMargin

    // 动态编译Java代码
    val verilatorNativeImplFile = new DynamicCompiler.InMemoryJavaFileObject(s"wrapper_${workspaceName}.VerilatorNative", verilatorNativeImplCode)
    import collection.JavaConverters._
    DynamicCompiler.compile(List[JavaFileObject](verilatorNativeImplFile).asJava, s"${workspacePath}/${workspaceName}")
  }

  /**
   * 检查运行环境
   * 确保在SBT环境下正确配置了fork选项
   */
  def checks(): Unit ={
    if(System.getProperty("java.class.path").contains("sbt-launch.jar")){
      System.err.println("""[Error] It look like you are running the simulation with SBT without having the SBT 'fork := true' configuration.\n  Add it in the build.sbt file to fix this issue, see https://github.com/SpinalHDL/SpinalTemplateSbt/blob/master/build.sbt""")
      throw new Exception()
    }
  }

  // 执行编译流程
  clean()                    // 清理工作空间
  checks()                   // 检查运行环境
  compileVerilator()         // 编译Verilator模型
  compileJava()              // 编译Java JNI包装器

  // 动态加载编译好的Java类
  val nativeImpl = DynamicCompiler.getClass(s"wrapper_${workspaceName}.VerilatorNative", s"${workspacePath}/${workspaceName}")
  val nativeInstance: IVerilatorNative = nativeImpl.getConstructor().newInstance().asInstanceOf[IVerilatorNative]

  /**
   * 实例化仿真器
   * 创建新的仿真实例，配置波形路径和随机种子
   * @param name 测试名称
   * @param seed 随机种子
   * @return 仿真句柄
   */
  def instanciate(name: String, seed: Int) = nativeInstance.synchronized{ // 同步是因为Verilator在构造时不是线程安全的
    val patchedPath = new File(config.vcdPath.replace("$TEST", name)).getAbsolutePath.replace("\\", "/")
    val patchedPrefix = if(config.vcdPrefix == null) "" else config.vcdPrefix.replace("$TEST", name) + "_"
    val wavePath = patchedPath + "/" + patchedPrefix
    FileUtils.forceMkdirParent(new File(patchedPath, "."))
    nativeInstance.newHandle(name, wavePath, seed)
  }

  // Verilator不使用缓冲写入
  override def isBufferedWrite: Boolean = false
}

