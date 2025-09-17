/*                                                                           *\
**        _____ ____  _____   _____    __                                    **
**       / ___// __ \/  _/ | / /   |  / /   HDL Core                         **
**       \__ \/ /_/ // //  |/ / /| | / /    (c) Dolu, All rights reserved    **
**      ___/ / ____// // /|  / ___ |/ /___                                   **
**     /____/_/   /___/_/ |_/_/  |_/_____/                                   **
**                                                                           **
**      This library is free software; you can redistribute it and/or        **
**    modify it under the terms of the GNU Lesser General Public             **
**    License as published by the Free Software Foundation; either           **
**    version 3.0 of the License, or (at your option) any later version.     **
**                                                                           **
**      This library is distributed in the hope that it will be useful,      **
**    but WITHOUT ANY WARRANTY; without even the implied warranty of         **
**    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU      **
**    Lesser General Public License for more details.                        **
**                                                                           **
**      You should have received a copy of the GNU Lesser General Public     **
**    License along with this library.                                       **
\*                                                                           */

/**
 * SpinalHDL仿真启动和配置核心文件
 *
 * 文件作用：
 * 这是SpinalHDL仿真系统的核心配置和启动文件，包含了所有仿真后端的配置类、
 * 工厂对象和编译流程。它是用户仿真代码和底层仿真器之间的桥梁。
 *
 * 在仿真流程中的位置：
 * 用户代码 -> SimConfig -> SimBootstraps -> 后端适配器 -> 底层仿真器
 *
 * 主要组件：
 * 1. SpinalVerilatorBackendConfig/SpinalVerilatorBackend - Verilator后端适配器
 * 2. SpinalIVerilogBackendConfig/SpinalIVerilogBackend - IVerilog后端适配器
 * 3. SpinalGhdlBackendConfig/SpinalGhdlBackend - GHDL后端适配器
 * 4. SpinalVCSBackendConfig/SpinalVCSBackend - VCS后端适配器
 * 5. SpinalXSimBackendConfig/SpinalXSimBackend - XSim后端适配器
 * 6. SimConfig - 统一的仿真配置接口
 * 7. SimCompiled - 编译后的仿真对象
 *
 * 核心功能：
 * - 将SpinalHDL的RTL报告转换为各种仿真器可理解的格式
 * - 自动发现和映射仿真信号
 * - 管理仿真工作空间和缓存
 * - 提供统一的编译和仿真接口
 * - 支持多种仿真后端的无缝切换
 */

package spinal.core.sim

import java.io.{File, PrintWriter}
import org.apache.commons.io.FileUtils
import spinal.core.internals.{BaseNode, DeclarationStatement, GraphUtils, PhaseCheck, PhaseContext, PhaseNetlist}
import spinal.core.{ASYNC, BaseType, Bits, BlackBox, Bool, Component, GlobalData, HIGH, InComponent, LOW, Mem, MemSymbolesMapping, MemSymbolesTag, RISING, SInt, ScopeProperty, SpinalConfig, SpinalEnumCraft, SpinalReport, SpinalTag, SpinalTagReady, SYNC, TimeNumber, UInt, Verilator, noLatchCheck}
import spinal.sim._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.util.Random
import sys.process._


/**
 * SpinalHDL Verilator后端配置类
 *
 * 这是SpinalHDL特定的Verilator配置，包含了SpinalReport和SpinalHDL特有的参数。
 * 它是VerilatorBackendConfig的高级封装，提供了更便于SpinalHDL使用的接口。
 *
 * 与VerilatorBackendConfig的区别：
 * - 直接接受SpinalReport[T]，包含完整的RTL信息
 * - 支持TimeNumber类型的时间精度设置
 * - 集成了SpinalHDL的测试路径管理
 * - 提供了类型安全的泛型参数T
 *
 * @param T 顶层组件类型，提供编译时类型安全
 * @param rtl SpinalHDL生成的RTL报告，包含HDL代码和元数据
 * @param waveFormat 波形格式 (VCD/FST/NONE)
 * @param maxCacheEntries 最大缓存条目数，用于编译结果缓存
 * @param cachePath 缓存路径，null表示禁用缓存
 * @param workspacePath 工作空间根路径
 * @param workspaceName 工作空间名称，用于隔离不同的编译
 * @param vcdPath VCD波形文件输出路径
 * @param vcdPrefix VCD文件名前缀
 * @param waveDepth 波形记录深度，0表示记录所有层级
 * @param optimisationLevel Verilator优化级别 (0-3)
 * @param simulatorFlags 传递给Verilator的额外标志
 * @param withCoverage 是否启用代码覆盖率分析
 * @param timePrecision 仿真时间精度，使用SpinalHDL的TimeNumber类型
 * @param testPath 测试输出路径，支持变量替换
 */
case class SpinalVerilatorBackendConfig[T <: Component](
                                                         rtl               : SpinalReport[T],
                                                         waveFormat        : WaveFormat = WaveFormat.NONE,
                                                         maxCacheEntries   : Int = 100,
                                                         cachePath         : String = null,
                                                         workspacePath     : String = "./",
                                                         workspaceName     : String = null,
                                                         vcdPath           : String = null,
                                                         vcdPrefix         : String = null,
                                                         waveDepth         : Int = 0,
                                                         optimisationLevel : Int = 2,
                                                         simulatorFlags    : ArrayBuffer[String] = ArrayBuffer[String](),
                                                         withCoverage      : Boolean,
                                                         timePrecision     : TimeNumber = null,
                                                         testPath          : String,
                                                         enableRtlAutoReset: Boolean = false  // RTL分析自动复位功能开关
)


/**
 * SpinalHDL Verilator后端工厂对象
 *
 * 这是SpinalHDL和底层VerilatorBackend之间的适配器，负责：
 * 1. 将SpinalHDL特定的配置转换为VerilatorBackend可理解的格式
 * 2. 从SpinalReport中提取信号信息并建立信号映射
 * 3. 处理SpinalHDL特有的数据类型和标签
 * 4. 创建并配置底层的VerilatorBackend实例
 * 5. 基于RTL分析进行复位信号发现和映射
 *
 * 设计模式：适配器模式 + 工厂模式
 * - 适配器：将SpinalHDL接口适配到Verilator接口
 * - 工厂：根据配置创建配置好的VerilatorBackend实例
 */
object SpinalVerilatorBackend {

  /**
   * 基于RTL分析的复位和时钟信号发现系统
   *
   * 这个方法遍历整个SpinalHDL设计，分析所有寄存器的时钟域配置，
   * 提取复位信号和时钟信号的准确信息，包括极性、类型等。
   */
  private def analyzeResetAndClockSignals(toplevel: Component, enableRtlAutoReset: Boolean): (Map[String, (String, Boolean, Boolean)], Map[String, Boolean]) = {
    import scala.collection.mutable

    val resetSignalMap = mutable.Map[String, (String, Boolean, Boolean)]()
    val clockSignalMap = mutable.Map[String, Boolean]()
    val allRegisters = mutable.Map[BaseType, Component]()  // 收集所有寄存器及其所属组件

    // 单次遍历：收集寄存器、时钟和复位信号，同时检测问题
    GraphUtils.walkAllComponents(toplevel, component => {
      component.dslBody.walkStatements { statement =>
        statement match {
          // 识别需要复位的寄存器
          case bt: BaseType if bt.isReg =>
            allRegisters(bt) = component  // 记录寄存器及其所属组件
            val cd = bt.clockDomain

            // 收集时钟信号信息
            val clockSignal = cd.clock
            val clockName = clockSignal.getName()
            val isRisingEdge = cd.config.clockEdge == RISING
            clockSignalMap(clockName) = isRisingEdge

            // 如果时钟域有复位信号，记录它
            if (cd.hasResetSignal) {
              val resetSignal = cd.reset
              val resetName = resetSignal.getName()
              val isAsync = cd.config.resetKind == ASYNC
              val isActiveLow = cd.config.resetActiveLevel == LOW
              val polarityDesc = if (isActiveLow) "LOW" else "HIGH"

              // 检查这个复位信号是否本身是一个寄存器（仅在启用自动复位时检查）
              if (enableRtlAutoReset) {
                allRegisters.find(_._1 == resetSignal) match {
                  case Some((resetReg, ownerComponent)) =>
                    // 这是一个寄存器被用作复位信号的情况
                    val hasInitValue = resetReg.hasInit
                    val isInResetDomain = resetReg.clockDomain.hasResetSignal

                    // 检查是否存在潜在问题
                    if (!hasInitValue || !isInResetDomain) {
                      // 发出警告但不添加到复位信号映射中
                      val componentPath = ownerComponent.getPath()
                      val issues = mutable.ArrayBuffer[String]()
                      if (!hasInitValue) issues += "没有初始值"
                      if (!isInResetDomain) issues += "位于无复位时钟域中"

                      println(s"[RTL自动复位警告] 检测到潜在的硬件设计问题:")
                      println(s"   复位信号: $resetName")
                      println(s"   所属组件: $componentPath")
                      println(s"   问题: ${issues.mkString(", ")}")
                      println(s"   风险: 该寄存器在真实硬件中可能有随机初始值，导致不可预测的复位行为")
                      println(s"   建议: 为该寄存器添加适当的初始值或将其放置在有复位的时钟域中")
                      println(s"   注意: 此复位信号将被排除在RTL自动复位处理之外，以避免掩盖硬件问题")
                      println()
                    }
                  case None =>
                    // 这是一个正常的复位信号（不是寄存器），可以安全处理
                }
              }

              // 记录复位信号信息：(极性描述, 是否异步, 是否低电平有效)
              resetSignalMap(resetName) = (polarityDesc, isAsync, isActiveLow)
            }

          case _ =>
        }
      }
    })

    (resetSignalMap.toMap, clockSignalMap.toMap)
  }

  /**
   * 创建配置好的VerilatorBackend实例
   *
   * 这个方法是SpinalHDL仿真系统的关键组件，它：
   * 1. 将高级的SpinalVerilatorBackendConfig转换为底层的VerilatorBackendConfig
   * 2. 遍历SpinalHDL的RTL结构，提取所有标记为public的信号
   * 3. 为每个信号分配唯一ID并建立类型映射
   * 4. 创建并返回配置完整的VerilatorBackend实例
   *
   * @param config SpinalHDL特定的Verilator配置
   * @return 配置完整的VerilatorBackend实例
   */
  def apply[T <: Component](config: SpinalVerilatorBackendConfig[T]) = {

    import config._

    // 1. 创建底层VerilatorBackendConfig并进行基本配置转换
    val vconfig = new VerilatorBackendConfig()
    vconfig.rtlIncludeDirs ++= rtl.rtlIncludeDirs           // RTL包含目录
    vconfig.rtlSourcesPaths ++= rtl.rtlSourcesPaths         // RTL源文件路径
    vconfig.toplevelName      = rtl.toplevelName            // 顶层模块名
    vconfig.vcdPath           = vcdPath                     // VCD输出路径
    vconfig.vcdPrefix         = vcdPrefix                   // VCD文件前缀
    vconfig.maxCacheEntries   = maxCacheEntries             // 缓存配置
    vconfig.cachePath         = cachePath
    vconfig.workspaceName     = workspaceName               // 工作空间配置
    vconfig.workspacePath     = workspacePath

    // 2. 波形格式转换（处理DEFAULT格式）
    vconfig.waveFormat        = waveFormat match {
      case WaveFormat.DEFAULT => WaveFormat.VCD            // 默认使用VCD格式
      case _ => waveFormat
    }
    vconfig.waveDepth         = waveDepth                   // 波形深度
    vconfig.optimisationLevel = optimisationLevel          // 优化级别
    vconfig.simulatorFlags    = simulatorFlags             // 仿真器标志
    vconfig.withCoverage      = withCoverage                // 覆盖率配置
    vconfig.autoInitialReset  = enableRtlAutoReset          // RTL分析自动复位功能开关

    // 2.5. 基于RTL分析的复位和时钟信号发现和映射（仅在启用自动复位时执行）
    if (enableRtlAutoReset) {
      // 这是真正基于SpinalHDL内部机制的普适性信号分析
      val (resetSignalAnalysis, clockSignalAnalysis) = analyzeResetAndClockSignals(rtl.toplevel, enableRtlAutoReset)
      vconfig.resetSignalMap = resetSignalAnalysis            // 传递复位信号分析结果
      vconfig.clockSignalMap = clockSignalAnalysis            // 传递时钟信号分析结果
    } else {
      // 禁用自动复位时，传递空映射
      vconfig.resetSignalMap = Map.empty
      vconfig.clockSignalMap = Map.empty
    }

    // 3. 时间精度转换（SpinalHDL TimeNumber -> String）
    vconfig.timePrecision = config.timePrecision match {
      case null => null
      case v => v.decomposeString                           // 将TimeNumber转换为字符串格式
    }

    // 4. 信号ID分配器，为每个可访问的信号分配唯一标识符
    var signalId = 0

    /**
     * 添加信号到仿真接口
     *
     * 这个内部函数负责：
     * 1. 根据SpinalHDL数据类型创建对应的仿真数据类型
     * 2. 构建信号的层次化路径名称
     * 3. 分配唯一的信号ID用于仿真器访问
     * 4. 将信号添加到VerilatorBackend的信号列表中
     *
     * @param bt SpinalHDL中的声明语句（信号或内存）
     */
    def addSignal(bt: DeclarationStatement with InComponent): Unit ={
      // 根据SpinalHDL类型创建对应的仿真数据类型
      val signal = new Signal(
        // 构建层次化信号路径：顶层模块名 + 组件路径 + 信号名
        config.rtl.toplevelName +: bt.getComponents().tail.map(_.getName()) :+ bt.getName(),
        bt match{
          case bt: Bool               => new BoolDataType                    // 布尔类型
          case bt: Bits               => new BitsDataType(bt.getBitsWidth)   // 位向量类型
          case bt: UInt               => new UIntDataType(bt.getBitsWidth)   // 无符号整数类型
          case bt: SInt               => new SIntDataType(bt.getBitsWidth)   // 有符号整数类型
          case bt: SpinalEnumCraft[_] => new BitsDataType(bt.getBitsWidth)   // 枚举类型（作为位向量处理）
          case mem: Mem[_]            => new BitsDataType(mem.width).setMem() // 内存类型
        }
      )

      // 设置SpinalHDL内部使用的算法标识符
      bt.algoInt = signalId                                 // 主要ID
      bt.algoIncrementale = -1                              // 增量ID（未使用时为-1）

      // 设置仿真器使用的信号ID并添加到配置中
      signal.id = signalId
      vconfig.signals += signal
      signalId += 1                                         // 为下一个信号准备ID
    }

    // 5. 遍历RTL层次结构，收集所有标记为public的信号
    // 这是信号发现和映射的核心过程
    GraphUtils.walkAllComponents(rtl.toplevel, c => c.dslBody.walkStatements(s => {
      s match {
        // 处理基本数据类型（Bool, Bits, UInt, SInt等）
        case bt: BaseType if bt.hasTag(Verilator.public) && !(!bt.isDirectionLess && bt.component.parent == null) => {
          addSignal(bt)                                     // 添加标记为public的基本类型信号
        }

        // 处理内存类型（Mem[_]）
        case mem : Mem[_] if mem.hasTag(Verilator.public) => {
          val tag = mem.getTag(classOf[MemSymbolesTag])     // 获取内存符号标签
          mem.algoInt = signalId                            // 设置内存的主ID
          mem.algoIncrementale = -1

          tag match {
            case None =>
              // 没有符号映射，直接添加整个内存
              addSignal(mem)
            case Some(tag) => {
              // 有符号映射，为每个映射的符号创建单独的信号
              // 这允许访问内存的不同部分或视图
              for(mapping <- tag.mapping){
                val signal = new Signal(
                  config.rtl.toplevelName +: mem.getComponents().tail.map(_.getName()) :+ mapping.name,
                  new BitsDataType(mapping.width).setMem()
                )
                signal.id = signalId
                vconfig.signals += signal
                signalId += 1
              }
            }
          }
        }

        // 其他语句设置为无效ID（不可访问）
        case _ =>{
          s.algoInt = -1                                    // 标记为不可访问
        }
      }
    }))

    // 6. 处理顶层模块的所有IO信号
    // IO信号总是可访问的，无需Verilator.public标签
    for(io <- rtl.toplevel.getAllIo){
      val bt = io
      // 为IO信号创建Signal对象，注意IO信号路径不包含顶层模块名
      val signal = new Signal(
        bt.getComponents().tail.map(_.getName()) :+ bt.getName(),  // IO信号的层次路径
        bt match{
          case bt: Bool               => new BoolDataType                    // 布尔IO
          case bt: Bits               => new BitsDataType(bt.getBitsWidth)   // 位向量IO
          case bt: UInt               => new UIntDataType(bt.getBitsWidth)   // 无符号整数IO
          case bt: SInt               => new SIntDataType(bt.getBitsWidth)   // 有符号整数IO
          case bt: SpinalEnumCraft[_] => new BitsDataType(bt.getBitsWidth)   // 枚举IO
        }
      )

      // 设置IO信号的标识符
      bt.algoInt = signalId
      bt.algoIncrementale = -1
      signal.id = signalId
      vconfig.signals += signal
      signalId += 1
    }

    // 7. 创建并返回配置完整的VerilatorBackend实例
    new VerilatorBackend(vconfig)
  }
}


/**
 * SpinalHDL Verilator仿真器工厂对象
 *
 * 提供创建SimVerilator实例的便利方法，支持两种创建方式：
 * 1. 从SpinalVerilatorBackendConfig直接创建（包含完整的编译过程）
 * 2. 从已有的VerilatorBackend创建（复用已编译的后端）
 *
 * 这个对象主要用于测试和快速原型开发，生产环境通常使用
 * SimConfig.compile().doSim()的完整流程。
 */
object SpinalVerilatorSim {
  /**
   * 从配置创建SimVerilator实例（包含编译过程）
   *
   * @param config SpinalHDL Verilator配置
   * @param seed 仿真随机种子
   * @return 配置好的SimVerilator实例
   */
  def apply[T <: Component](config: SpinalVerilatorBackendConfig[T], seed: Int) : SimVerilator = {
    val backend = SpinalVerilatorBackend(config)           // 创建并编译后端
    SpinalVerilatorSim(backend, seed)                      // 创建仿真实例
  }

  /**
   * 从已有后端创建SimVerilator实例（复用编译结果）
   *
   * @param backend 已编译的VerilatorBackend实例
   * @param seed 仿真随机种子
   * @return 配置好的SimVerilator实例
   */
  def apply[T <: Component](backend : VerilatorBackend, seed : Int) : SimVerilator = {
    val sim = new SimVerilator(backend, backend.instanciate("test1", seed))  // 实例化仿真器
    sim.userData = backend.config.signals                  // 设置信号列表供验证使用
    sim
  }
}

class SpinalVpiBackendConfig[T <: Component](val rtl               : SpinalReport[T],
                                             val waveFormat       : WaveFormat,
                                             val workspacePath    : String,
                                             val workspaceName    : String,
                                             val wavePath         : String,
                                             val wavePrefix       : String,
                                             val waveDepth        : Int,
                                             val optimisationLevel: Int,
                                             val simulatorFlags   : ArrayBuffer[String],
                                             val runFlags         : ArrayBuffer[String],
                                             val usePluginsCache  : Boolean,
                                             val pluginsCachePath : String,
                                             val enableLogging    : Boolean,
                                             val timePrecision    : TimeNumber,
                                             val testPath         : String)


case class SpinalIVerilogBackendConfig[T <: Component](override val rtl : SpinalReport[T],
                                                   override val waveFormat        : WaveFormat = WaveFormat.NONE,
                                                   override val workspacePath     : String = "./",
                                                   override val workspaceName     : String = null,
                                                   override val wavePath           : String = null,
                                                   override val wavePrefix         : String = null,
                                                   override val waveDepth         : Int = 0,
                                                   override val optimisationLevel : Int = 2,
                                                   override val simulatorFlags    : ArrayBuffer[String] = ArrayBuffer[String](),
                                                   override val runFlags          : ArrayBuffer[String] = ArrayBuffer[String](),
                                                   override val usePluginsCache   : Boolean = true,
                                                   override val pluginsCachePath  : String = "./simWorkspace/.pluginsCachePath",
                                                   override val enableLogging     : Boolean = false,
                                                   override val timePrecision     : TimeNumber = null,
                                                   override val testPath          : String = null) extends
                                              SpinalVpiBackendConfig[T](rtl,
                                                                        waveFormat,
                                                                        workspacePath,
                                                                        workspaceName,
                                                                        wavePath,
                                                                        wavePrefix,
                                                                        waveDepth,
                                                                        optimisationLevel,
                                                                        simulatorFlags,
                                                                        runFlags,
                                                                        usePluginsCache,
                                                                        pluginsCachePath,
                                                                        enableLogging,
                                                                        timePrecision,
                                                                        testPath)


case class SpinalVCSBackendConfig[T <: Component](override val rtl : SpinalReport[T],
                                                  override val waveFormat        : WaveFormat = WaveFormat.NONE,
                                                  override val workspacePath     : String = "./",
                                                  override val workspaceName     : String = null,
                                                  override val wavePath          : String = null,
                                                  override val wavePrefix        : String = null,
                                                  override val waveDepth         : Int = 0,
                                                  override val optimisationLevel : Int = 2,
                                                  override val simulatorFlags    : ArrayBuffer[String] = ArrayBuffer[String](),
                                                  override val runFlags          : ArrayBuffer[String] = ArrayBuffer[String](),
                                                  override val usePluginsCache   : Boolean = true,
                                                  override val pluginsCachePath  : String = "./simWorkspace/.pluginsCachePath",
                                                  override val enableLogging     : Boolean = false,
                                                  override val timePrecision     : TimeNumber = null,
                                                  val simSetupFile               : String = null,
                                                  val envSetup                   : () => Unit = null,
                                                  val vcsFlags                   : VCSFlags = null,
                                                  val compileFlags               : ArrayBuffer[String] = ArrayBuffer[String](),
                                                  val elaborateFlags             : ArrayBuffer[String] = ArrayBuffer[String](),
                                                  val vcsCC                      : Option[String] = None,
                                                  val vcsLd                      : Option[String] = None,
                                                  override val testPath          : String = null) extends
  SpinalVpiBackendConfig[T](rtl,
    waveFormat,
    workspacePath,
    workspaceName,
    wavePath,
    wavePrefix,
    waveDepth,
    optimisationLevel,
    simulatorFlags,
    runFlags,
    usePluginsCache,
    pluginsCachePath,
    enableLogging,
    timePrecision,
    testPath)

case class SpinalGhdlBackendConfig[T <: Component](override val rtl : SpinalReport[T],
                                                   override val waveFormat        : WaveFormat = WaveFormat.NONE,
                                                   override val workspacePath     : String = "./",
                                                   override val workspaceName     : String = null,
                                                   override val wavePath           : String = null,
                                                   override val wavePrefix         : String = null,
                                                   override val waveDepth         : Int = 0,
                                                   override val optimisationLevel : Int = 2,
                                                   override val simulatorFlags    : ArrayBuffer[String] = ArrayBuffer[String](),
                                                   override val runFlags          : ArrayBuffer[String] = ArrayBuffer[String](),
                                                   override val usePluginsCache   : Boolean = true,
                                                   override val pluginsCachePath  : String = "./simWorkspace/.pluginsCachePath",
                                                   override val enableLogging     : Boolean = false,
                                                   override val timePrecision     : TimeNumber = null,
                                                   val ghdlFlags : GhdlFlags = GhdlFlags(),
                                                   override  val testPath         : String = null
) extends
                                              SpinalVpiBackendConfig[T](rtl,
                                                                        waveFormat,
                                                                        workspacePath,
                                                                        workspaceName,
                                                                        wavePath,
                                                                        wavePrefix,
                                                                        waveDepth,
                                                                        optimisationLevel,
                                                                        simulatorFlags,
                                                                        runFlags,
                                                                        usePluginsCache,
                                                                        pluginsCachePath,
                                                                        enableLogging,
                                                                        timePrecision,
                                                                        testPath)


object SpinalGhdlBackend {
  class Backend(val signals : ArrayBuffer[Signal], vconfig : GhdlBackendConfig) extends GhdlBackend(vconfig)

  def apply[T <: Component](config: SpinalGhdlBackendConfig[T]) : Backend = {
    val vconfig = new GhdlBackendConfig()
    val flagsConcat = config.simulatorFlags.mkString(" ")
    vconfig.analyzeFlags = flagsConcat
    vconfig.elaborationFlags = config.ghdlFlags.elaborationFlags.mkString(" ") + {
      if (config.timePrecision != null) {
        s" --time-resolution=${config.timePrecision.decompose._2}"
      } else ""
    }
    vconfig.runFlags = config.runFlags.mkString(" ")
    vconfig.logSimProcess = config.enableLogging
    vconfig.testPath = config.testPath

    val signalsCollector = SpinalVpiBackend(config, vconfig)

    new Backend(signalsCollector, vconfig)
  }
}

object SpinalIVerilogBackend {
  class Backend(val signals : ArrayBuffer[Signal], vconfig : IVerilogBackendConfig) extends IVerilogBackend(vconfig)

  def apply[T <: Component](config: SpinalIVerilogBackendConfig[T]) = {
    val vconfig = new IVerilogBackendConfig()
    vconfig.analyzeFlags = config.simulatorFlags.mkString(" ")
    vconfig.runFlags = config.simulatorFlags.mkString(" ")
    vconfig.logSimProcess = config.enableLogging
    vconfig.testPath = config.testPath
    vconfig.timePrecision = config.timePrecision match {
      case null => null
      case t => t.decomposeString
    }

    val signalsCollector = SpinalVpiBackend(config, vconfig)

    new Backend(signalsCollector, vconfig)
  }
}

object SpinalVCSBackend {
  class Backend(val signals : ArrayBuffer[Signal], vconfig : VCSBackendConfig) extends VCSBackend(vconfig)

  def apply[T <: Component](config: SpinalVCSBackendConfig[T]) = {
    val vconfig = new VCSBackendConfig()
    vconfig.flags = config.vcsFlags
    vconfig.logSimProcess = config.enableLogging
    vconfig.vcsLd = config.vcsLd
    vconfig.vcsCC = config.vcsCC
    vconfig.waveDepth = config.waveDepth
    vconfig.wavePath = config.wavePath
    vconfig.wavePrefix = config.wavePrefix
    vconfig.simSetupFile = config.simSetupFile
    vconfig.envSetup = config.envSetup
    vconfig.testPath = config.testPath
    vconfig.timePrecision = config.timePrecision match {
      case null => null
      case t => t.decomposeString
    }

    val signalsCollector = SpinalVpiBackend(config, vconfig)

    new Backend(signalsCollector, vconfig)
  }
}

object SpinalVpiBackend {

  def apply[T <: Component](config: SpinalVpiBackendConfig[T], vconfig: VpiBackendConfig) = {

    import config._

    vconfig.rtlIncludeDirs  ++= rtl.rtlIncludeDirs
    vconfig.rtlSourcesPaths ++= rtl.rtlSourcesPaths.map(new File(_).getAbsolutePath)
    vconfig.toplevelName      = rtl.toplevelName
    vconfig.wavePath          = "test.vcd"
    vconfig.waveFormat        = waveFormat match {
      case WaveFormat.DEFAULT => WaveFormat.VCD
      case _ => waveFormat
    }
    vconfig.workspaceName     = workspaceName
    vconfig.workspacePath     = workspacePath
    vconfig.useCache = usePluginsCache
    vconfig.timePrecision = config.timePrecision match {
      case null => null
      case t => t.decomposeString
    }
    vconfig.pluginsPath = if(usePluginsCache) {

    val pluginsCachePathFile = new File(pluginsCachePath)
      if(!pluginsCachePathFile.exists()) {
        pluginsCachePathFile.mkdirs
      }
      pluginsCachePath
    } else workspacePath

    var signalId = 0

    val signalsCollector = ArrayBuffer[Signal]()

    def addSignal(bt: DeclarationStatement with InComponent): Unit ={
      val signal = new Signal(config.rtl.toplevelName +: bt.getComponents().tail.map(_.getName()) :+ bt.getName(), bt match{
        case bt: Bool               => new BoolDataType
        case bt: Bits               => new BitsDataType(bt.getBitsWidth)
        case bt: UInt               => new UIntDataType(bt.getBitsWidth)
        case bt: SInt               => new SIntDataType(bt.getBitsWidth)
        case bt: SpinalEnumCraft[_] => new BitsDataType(bt.getBitsWidth)
        case mem: Mem[_] => new BitsDataType(mem.width)
      })

      bt.algoInt = signalId
      bt.algoIncrementale = -1
      signal.id = signalId
      signalsCollector += signal
      signalId += 1
    }

    GraphUtils.walkAllComponents(rtl.toplevel, c => c.dslBody.walkStatements(s => {
      s match {
        case bt: BaseType if bt.hasTag(SimPublic) && !(!bt.isDirectionLess && bt.component.parent == null) => {
          addSignal(bt)
        }
        case mem : Mem[_] if mem.hasTag(SimPublic) => {
          val tag = mem.getTag(classOf[MemSymbolesTag])
          mem.algoInt = signalId
          mem.algoIncrementale = -1
          tag match {
            case None => addSignal(mem)
            case Some(tag) => {
              for(mapping <- tag.mapping){
                val signal =  new Signal(config.rtl.toplevelName +: mem.getComponents().tail.map(_.getName()) :+ mapping.name, new BitsDataType(mapping.width))
                signal.id = signalId
                signalsCollector += signal
                signalId += 1
              }
            }
          }
        }
        case _ =>{
          s.algoInt = -1
        }
      }
    }))

    for(io <- rtl.toplevel.getAllIo){
      val bt = io
      val signal = new Signal(config.rtl.toplevelName +: bt.getComponents().tail.map(_.getName()) :+ bt.getName(), bt match{
        case bt: Bool               => new BoolDataType
        case bt: Bits               => new BitsDataType(bt.getBitsWidth)
        case bt: UInt               => new UIntDataType(bt.getBitsWidth)
        case bt: SInt               => new SIntDataType(bt.getBitsWidth)
        case bt: SpinalEnumCraft[_] => new BitsDataType(bt.getBitsWidth)
      })

      bt.algoInt = signalId
      bt.algoIncrementale = -1
      signal.id = signalId
      signalsCollector += signal
      signalId += 1
    }
    signalsCollector
  }
}

case class SpinalXSimBackendConfig[T <: Component](val rtl               : SpinalReport[T],
                                               val xciSourcesPaths  : ArrayBuffer[String] = ArrayBuffer[String](),
                                               val bdSourcesPaths   : ArrayBuffer[String] = ArrayBuffer[String](),
                                               val waveFormat       : WaveFormat,
                                               val workspacePath    : String,
                                               val workspaceName    : String,
                                               val wavePath         : String,
                                               val xilinxDevice     : String,
                                               val simScript        : String,
                                               val simulatorFlags   : ArrayBuffer[String] = ArrayBuffer[String](),
                                               val timePrecision    : TimeNumber = null)

object SpinalXSimBackend {
  class Backend(val signals : ArrayBuffer[Signal], vconfig : XSimBackendConfig) extends XSimBackend(vconfig)
  def apply[T <: Component](config: SpinalXSimBackendConfig[T]) = {
    import config._

    val vconfig = new XSimBackendConfig()
    vconfig.rtlIncludeDirs  ++= rtl.rtlIncludeDirs
    vconfig.rtlSourcesPaths ++= rtl.rtlSourcesPaths.map(new File(_).getAbsolutePath)
    vconfig.xciSourcesPaths   =  xciSourcesPaths
    vconfig.bdSourcesPaths    = bdSourcesPaths
    vconfig.toplevelName      = rtl.toplevelName
    vconfig.waveFormat        = waveFormat match {
      case WaveFormat.DEFAULT => WaveFormat.WDB
      case _ => waveFormat
    }
    vconfig.workspaceName     = workspaceName
    vconfig.workspacePath     = workspacePath
    vconfig.wavePath          = s"${workspacePath}/${workspaceName}/${rtl.toplevelName}.wdb"
    vconfig.xilinxDevice      = xilinxDevice
    vconfig.userSimulationScript = simScript
    vconfig.xelabFlags        = simulatorFlags.toArray
    vconfig.timePrecision     = if (timePrecision != null) timePrecision.decomposeString else null

    var signalId = 0

    val signalsCollector = ArrayBuffer[Signal]()

    for(io <- rtl.toplevel.getAllIo){
      val bt = io
      val signal = new Signal(config.rtl.toplevelName +: bt.getComponents().tail.map(_.getName()) :+ bt.getName(), bt match{
        case bt: Bool               => new BoolDataType
        case bt: Bits               => new BitsDataType(bt.getBitsWidth)
        case bt: UInt               => new UIntDataType(bt.getBitsWidth)
        case bt: SInt               => new SIntDataType(bt.getBitsWidth)
        case bt: SpinalEnumCraft[_] => new BitsDataType(bt.getBitsWidth)
      })

      bt.algoInt = signalId
      bt.algoIncrementale = -1
      signal.id = signalId
      signalsCollector += signal
      signalId += 1
    }
    new Backend(signalsCollector, vconfig)
  }
}

/** Tag SimPublic  */
object SimPublic extends SpinalTag

object TracingOff extends SpinalTag

/**
  * Swap all oldTag with newTag
  */
class SwapTagPhase(oldOne: SpinalTag, newOne: SpinalTag) extends PhaseNetlist {

  override def impl(pc: PhaseContext): Unit = {
    pc.walkDeclarations{
      case x: SpinalTagReady if(x.hasTag(oldOne)) =>  {
        x.removeTag(oldOne)
        x.addTag(newOne)
      }
      case _ =>
    }
  }
}


/**
 * Verilator仿真阶段处理器
 * 在网表阶段为Verilator仿真做准备工作
 * 主要功能：
 * 1. 为标记为SimPublic的信号添加Verilator.public标签
 * 2. 为需要的组件添加LATCH检查禁用注释
 */
class SimVerilatorPhase extends PhaseNetlist {

  override def impl(pc: PhaseContext): Unit = {
    val latchesIn = mutable.LinkedHashSet[Component]()

    // 遍历所有声明
    pc.walkDeclarations { d =>
      d match {
        // 为SimPublic标记的信号添加Verilator public标签
        // 这样Verilator会将这些信号暴露给C++接口
        case x: SpinalTagReady if (x.hasTag(SimPublic)) => {
          x.addTag(Verilator.public)
        }
        case _ =>
      }
      d match {
        // 收集需要禁用LATCH检查的组件
        case bt: BaseType if bt.hasTag(noLatchCheck) => latchesIn += bt.component
        case _ =>
      }
    }

    // 为需要的组件添加Verilator LATCH检查禁用注释
    latchesIn.foreach(_.definition.addComment("verilator lint_off LATCH"))
  }
}

class CoreSimManager(sim : SimRaw, random : Random, allocatedName : String, val compiled : SimCompiled[_ <: Component]) extends SimManager(sim, random, allocatedName){
  val spinalGlobalData =  GlobalData.get
  override def setupJvmThread(thread: Thread): Unit = {
    super.setupJvmThread(thread)
    GlobalData.it.set(spinalGlobalData)
  }

  override def newSpawnTask() = new SimThreadSpawnTask {
    val initialContext = ScopeProperty.capture()
    override def setup() = initialContext.restore()
  }
}

/**
  * Run simulation
  */
abstract class SimCompiled[T <: Component](val report: SpinalReport[T], val compiledPath : File, val simConfig : SpinalSimConfig){
  def dut = report.toplevel

  val testNameMap = mutable.HashMap[String, Int]()

  def allocateTestName(name: String): String = {
    testNameMap.synchronized{
      val value = testNameMap.getOrElseUpdate(name, 0)
      testNameMap(name) = value + 1
      if(value == 0){
        return name
      }else{
        val ret = name + "_" + value
        println(s"[Info] Test '$name' was reallocated as '$ret' to avoid collision")
        return ret
      }
    }
  }

  def doSim(body: T => Unit): Unit =  doSimApi(joinAll = false)(body)
  def doSim(name: String)(body: T => Unit): Unit = doSimApi(name = name, joinAll = false)(body)
  def doSim(seed: Int)(body: T => Unit): Unit = doSimApi(seed = seed, joinAll = false)(body)
  def doSim(name: String, seed: Int)(body : T => Unit): Unit = {
    doSimApi(name, seed, false)(body)
  }

  def doSimUntilVoid(body: T => Unit): Unit =  doSimApi(joinAll = true)(body)
  def doSimUntilVoid(name: String)(body: T => Unit): Unit = doSimApi(name = name, joinAll = true)(body)
  def doSimUntilVoid(seed: Int)(body: T => Unit): Unit = doSimApi(seed = seed, joinAll = true)(body)
  def doSimUntilVoid(name: String, seed: Int)(body : T => Unit): Unit = {
    doSimApi(name, seed, true)(body)
  }

  /**
   * 创建新的SimRaw实例（抽象方法）
   *
   * 这是一个抽象方法，由具体的SimCompiled子类实现。
   * 每个仿真后端（Verilator、GHDL、VCS等）都有自己的实现方式。
   *
   * 职责：
   * 1. 根据后端类型创建对应的SimRaw实现（SimVerilator、SimVpi等）
   * 2. 初始化仿真器实例，建立与底层仿真器的连接
   * 3. 设置仿真参数（随机种子、波形路径等）
   * 4. 返回可用的SimRaw接口供SimManager使用
   *
   * @param name 测试名称，用于标识仿真实例和生成波形文件名
   * @param seed 随机种子，确保仿真的可重现性
   * @return SimRaw实例，提供底层仿真器访问接口
   *
   * 实现示例：
   * - Verilator: 创建SimVerilator，连接到编译好的C++模型
   * - GHDL: 创建SimVpi，通过VPI接口连接到GHDL仿真器
   * - VCS: 创建SimVpi，通过VPI接口连接到VCS仿真器
   */
  def newSimRaw(name: String, seed: Int) : SimRaw

  /**
   * 生成新的随机种子
   *
   * 优先级：
   * 1. 环境变量SPINAL_SIM_SEED（用于可重现的测试）
   * 2. 随机生成（用于随机化测试）
   *
   * @return 随机种子值
   */
  def newSeed(): Int = {
    sys.env.get("SPINAL_SIM_SEED") match {
      case Some(v) => v.toInt
      case None => Random.nextInt(Integer.MAX_VALUE)
    }
  }

  /**
   * 仿真API核心实现方法
   *
   * 这是所有doSim和doSimUntilVoid方法的底层实现，负责：
   * 1. 设置仿真环境和全局状态
   * 2. 创建和配置仿真管理器
   * 3. 执行用户仿真代码
   * 4. 管理仿真生命周期
   *
   * 执行流程：
   * 1. 创建随机数生成器，确保可重现性
   * 2. 设置SpinalHDL全局数据上下文
   * 3. 分配唯一的测试名称，避免冲突
   * 4. 创建SimRaw实例，建立与仿真器的连接
   * 5. 创建CoreSimManager，管理仿真执行
   * 6. 根据joinAll参数选择执行模式
   *
   * @param name 测试名称，默认为"test"
   * @param seed 随机种子，默认调用newSeed()生成
   * @param joinAll 执行模式标志
   *                - true: 使用runAll()，等待所有fork线程完成
   *                - false: 使用run()，主线程结束即停止
   * @param body 用户仿真代码，接收DUT实例作为参数
   *
   * 设计要点：
   * - seed为0时自动转换为1，避免某些仿真器的问题
   * - 使用allocateTestName确保测试名称唯一性
   * - CoreSimManager继承自SimManager，添加了SpinalHDL特定功能
   * - 通过manager.userData传递DUT引用
   */
  def doSimApi(name: String = "test", seed: Int = newSeed(), joinAll: Boolean)(body: T => Unit): Unit = {
    val random = new Random(seed)                    // 创建可重现的随机数生成器
    GlobalData.set(report.globalData)                // 设置SpinalHDL全局数据上下文

    val allocatedName = allocateTestName(name)       // 分配唯一的测试名称
    val backendSeed   = if(seed == 0) 1 else seed    // 避免seed为0的潜在问题

    val sim = newSimRaw(allocatedName, backendSeed)  // 创建底层仿真接口

    val manager = new CoreSimManager(sim, random, allocatedName, this)  // 创建仿真管理器
    manager.userData = dut                           // 设置DUT引用

    println(f"[Progress] Start ${dut.definitionName} $allocatedName simulation with seed $seed")

    if(joinAll) {
      manager.runAll(body(dut))                      // 等待所有fork线程完成
    }else {
      manager.run(body(dut))                         // 主线程结束即停止
    }
  }
}


/**
  * Simulation Workspace
  */
object SimWorkspace {
  private var uniqueId = 0

  def allocateUniqueId(): Int = {
    this.synchronized {
      uniqueId = uniqueId + 1
      uniqueId
    }
  }

  val workspaceMap = mutable.HashMap[(String, String), Int]()

  def allocateWorkspace(path: String, name: String): String = {
    workspaceMap.synchronized{
      val value = workspaceMap.getOrElseUpdate((path,name), 0)
      workspaceMap((path, name)) = value + 1
      if(value == 0){
        return name
      }else{
        val ret = name + "_" + value
        println(s"[Info] Workspace '$name' was reallocated as '$ret' to avoid collision")
        return ret
      }
    }
  }
}

class SpinalSimBackendSel
object SpinalSimBackendSel{
  val VERILATOR = new SpinalSimBackendSel
  val GHDL = new SpinalSimBackendSel
  val IVERILOG = new SpinalSimBackendSel
  val VCS = new SpinalSimBackendSel
  val XSIM = new SpinalSimBackendSel
}

/**
  * SpinalSim configuration
  */
case class SpinalSimConfig(
                            var _workspacePath     : String = System.getenv().getOrDefault("SPINALSIM_WORKSPACE","./simWorkspace"),
                            var _workspaceName     : String = null,
                            var _waveDepth         : Int = 0, //0 => all
                            var _spinalConfig      : SpinalConfig = SpinalConfig(),
                            var _optimisationLevel : Int = 0,
                            var _simulatorFlags    : ArrayBuffer[String] = ArrayBuffer[String](),
                            var _runFlags          : ArrayBuffer[String] = ArrayBuffer[String](),
                            var _additionalRtlPath : ArrayBuffer[String] = ArrayBuffer[String](),
                            var _additionalIncludeDir : ArrayBuffer[String] = ArrayBuffer[String](),
                            var _waveFormat        : WaveFormat = WaveFormat.NONE,
                            var _backend           : SpinalSimBackendSel = SpinalSimBackendSel.VERILATOR,
                            var _withCoverage      : Boolean = false,
                            var _maxCacheEntries   : Int = 100,
                            var _cachePath         : String = null, // null => workspacePath + "/.cache"
                            var _disableCache      : Boolean = false,
                            var _withLogging       : Boolean = false,
                            var _vcsCC             : Option[String] = None,
                            var _vcsLd             : Option[String] = None,
                            var _vcsUserFlags      : VCSFlags = VCSFlags(),
                            var _vcsSimSetupFile   : String = null,
                            var _vcsEnvSetup       : () => Unit = null,
                            var _xciSourcesPaths   : ArrayBuffer[String] = ArrayBuffer[String](),
                            var _bdSourcesPaths    : ArrayBuffer[String] = ArrayBuffer[String](),
                            var _xilinxDevice:String = "xc7vx485tffg1157-1",
                            var _simScript         : String = null,
                            var _timePrecision     : TimeNumber = null,
                            var _timeScale         : TimeNumber = null,
                            var _testPath          : String = "$WORKSPACE/$COMPILED/$TEST",
                            var _waveFilePrefix    : String = null,
                            var _ghdlFlags: GhdlFlags = GhdlFlags(),
                            var _enableRtlAutoReset: Boolean = false  // RTL分析自动复位功能开关，默认启用
  ){


  def  withVerilator : this.type = {
    _backend = SpinalSimBackendSel.VERILATOR
    this
  }
  def withGHDL(ghdlFlags: GhdlFlags = GhdlFlags()) = {
    _ghdlFlags = ghdlFlags
    withGhdl()
  }

  def  withGhdl() : this.type = {
    _backend = SpinalSimBackendSel.GHDL
    this
  }
  def  withIVerilog : this.type = {
    _backend = SpinalSimBackendSel.IVERILOG
    this
  }

  def withVcs : this.type = withVCS
  def withVCS : this.type = {
    _backend = SpinalSimBackendSel.VCS
    this
  }

  def withVCS(vcsFlags: VCSFlags = VCSFlags()) : this.type = {
    _backend = SpinalSimBackendSel.VCS
    _vcsUserFlags = vcsFlags
    this
  }

  def withVCSSimSetup(setupFile: String, beforeAnalysis: () => Unit): this.type = {
    _vcsSimSetupFile = setupFile
    _vcsEnvSetup = beforeAnalysis
    this
  }

  def withXSim: this.type = {
    _backend = SpinalSimBackendSel.XSIM
    this
  }

  def withXSimSourcesPaths(xciSourcesPaths: ArrayBuffer[String], bdSourcesPaths: ArrayBuffer[String]): this.type = {
    _xciSourcesPaths = xciSourcesPaths
    _bdSourcesPaths = bdSourcesPaths
    this
  }

  def withSimScript(script: String): this.type = {
    _simScript = script
    this
  }

  def withVPDWave: this.type = {
    _waveFormat = WaveFormat.VPD
    this
  }
  def withFSDBWave: this.type = {
    _waveFormat = WaveFormat.FSDB
    this
  }

  def withVCSCc(cc: String) : this.type = {
    _vcsCC = Some(cc)
    this
  }
  def withVCSLd(ld: String) : this.type = {
    _vcsLd = Some(ld)
    this
  }

  def withVcdWave : this.type = {
    _waveFormat = WaveFormat.VCD
    this
  }

  def withFstWave : this.type = {
    _waveFormat = WaveFormat.FST
    this
  }

  def withFsdbWave : this.type = {
    _waveFormat = WaveFormat.FSDB
    this
  }

  def withVpdWave : this.type = {
    _waveFormat = WaveFormat.VPD
    this
  }

  def withWave: this.type = {
    _waveFormat = WaveFormat.DEFAULT
    this
  }

  def withWaveDepth(depth: Int): this.type = {
    _waveDepth = depth
    this
  }

  def withWave(depth: Int): this.type = {
    _waveFormat = WaveFormat.DEFAULT
    _waveDepth = depth
    this
  }

  def withCoverage: this.type = {
    _withCoverage = true
    this
  }

  def withLogging: this.type = {
    _withLogging = true
    this
  }

  def withXilinxDevice(xilinxDevice:String):this.type ={
    _xilinxDevice = xilinxDevice
    this
  }

  def workspacePath(path: String): this.type = {
    _workspacePath = path
    this
  }

  def workspaceName(name: String): this.type = {
    _workspaceName = name
    this
  }

  def waveFilePrefix(prefix: String): this.type = {
    _waveFilePrefix = prefix
    this
  }

  def withConfig(config: SpinalConfig): this.type = {
    _spinalConfig = config
    this
  }

  def noOptimisation: this.type = {
    _optimisationLevel = 0
    this
  }
  def fewOptimisation: this.type = {
    _optimisationLevel = 1
    this
  }
  def normalOptimisation: this.type = {
    _optimisationLevel = 2
    this
  }
  def allOptimisation: this.type = {
    _optimisationLevel = 3
    this
  }

  def addSimulatorFlag(flag: String): this.type = {
    _simulatorFlags += flag
    this
  }

  def addRunFlag(flag: String): this.type = {
    _runFlags += flag
    this
  }

  def addRtl(that : String) : this.type = {
    _additionalRtlPath += that
    this
  }

  def addIncludeDir(that : String) : this.type = {
    _additionalIncludeDir += that
    this
  }

  def maxCacheEntries(count: Int): this.type = {
    _maxCacheEntries = count
    this
  }

  def cachePath(path: String): this.type = {
    _cachePath = path
    this
  }

  def disableCache: this.type = {
    _disableCache = true
    this
  }

  def withTimeScale(timeScale: TimeNumber): this.type = {
    _timeScale = timeScale
    this
  }

  def withTimePrecision(timePrecision: TimeNumber): this.type = {
    _timePrecision = timePrecision
    this
  }

  def withTimeSpec(timeScale: TimeNumber, timePrecision: TimeNumber): this.type = {
    withTimeScale(timeScale)
    withTimePrecision(timePrecision)
    this
  }

  def setTestPath(path : String) : this.type = {
    _testPath = path
    this
  }

  def getTestPath(test : String) = _testPath.replace("$TEST", test)

  def withTestFolder : this.type = {
    this.setTestPath("$WORKSPACE/$COMPILED/$TEST")
    this
  }

  /**
   * 禁用RTL分析自动复位功能
   *
   * 禁用后将不会执行自动复位序列，需要用户手动处理复位逻辑。
   * 主要用于调试或与传统仿真方式兼容的场景。
   *
   * @return 当前配置对象，支持链式调用
   */
  def withAutoReset: this.type = {
    _enableRtlAutoReset = true
    this
  }

  def addOptions(parser: scopt.OptionParser[Unit]): Unit = {
    import parser._
    opt[Unit]("trace-fst") action { (v, c) => this.withFstWave }
    opt[Unit]("trace-vcd") action { (v, c) => this.withVcdWave }
  }

  def doSim[T <: Component](report: SpinalReport[T])(body: T => Unit): Unit = compile(report).doSim(body)
  def doSim[T <: Component](report: SpinalReport[T], name: String)(body: T => Unit): Unit = compile(report).doSim(name)(body)
  def doSim[T <: Component](report: SpinalReport[T], name: String, seed: Int)(body: T => Unit): Unit = compile(report).doSim(name, seed)(body)

  def doSimUntilVoid[T <: Component](report: SpinalReport[T])(body: T => Unit): Unit = compile(report).doSimUntilVoid(body)
  def doSimUntilVoid[T <: Component](report: SpinalReport[T], name: String)(body: T => Unit): Unit = compile(report).doSimUntilVoid(name)(body)
  def doSimUntilVoid[T <: Component](report: SpinalReport[T], name: String, seed: Int)(body: T => Unit): Unit = compile(report).doSimUntilVoid(name, seed)(body)

  def doSim[T <: Component](rtl: => T)(body: T => Unit): Unit = compile(rtl).doSim(body)
  def doSim[T <: Component](rtl: => T, name: String)(body: T => Unit): Unit = compile(rtl).doSim(name)(body)
  def doSim[T <: Component](rtl: => T, name: String, seed: Int)(body: T => Unit): Unit = compile(rtl).doSim(name, seed)(body)

  def doSimUntilVoid[T <: Component](rtl: => T)(body: T => Unit): Unit = compile(rtl).doSimUntilVoid(body)
  def doSimUntilVoid[T <: Component](rtl: => T, name: String)(body: T => Unit): Unit = compile(rtl).doSimUntilVoid(name)(body)
  def doSimUntilVoid[T <: Component](rtl: => T, name: String, seed: Int)(body: T => Unit): Unit = compile(rtl).doSimUntilVoid(name,seed)(body)

  /**
   * 编译RTL代码生成器为仿真对象
   *
   * 这个方法接受一个RTL代码生成器（按名传递），会先克隆当前配置，
   * 然后在克隆的配置上执行编译，避免修改原始配置对象。
   *
   * @param rtl RTL代码生成器（按名传递，延迟执行）
   * @return 编译好的仿真对象
   *
   * 设计原因：
   * 1. 使用this.copy()克隆配置是为了避免编译过程修改原始配置
   * 2. 这样可以支持同一个配置对象多次编译不同的RTL
   * 3. 每次编译都在独立的工作空间中进行，避免冲突
   */
  def compile[T <: Component](rtl: => T) : SimCompiled[T] = {
    this.copy().compileCloned(rtl)
  }

  /**
   * 在克隆的配置上编译RTL代码
   *
   * 这个方法执行实际的RTL编译工作：
   * 1. 设置临时工作目录
   * 2. 配置SpinalHDL编译参数
   * 3. 根据后端类型生成对应的HDL代码
   * 4. 调用compile(report)进行后端编译
   *
   * @param rtl RTL代码生成器
   * @return 编译好的仿真对象
   */
  def compileCloned[T <: Component](rtl: => T) : SimCompiled[T] = {
    // 处理用户主目录路径
    if (_workspacePath.startsWith("~"))
      _workspacePath = System.getProperty("user.home") + _workspacePath.drop(1)

    // 分配唯一ID和创建临时目录
    val uniqueId = SimWorkspace.allocateUniqueId()
    new File(s"${_workspacePath}/tmp").mkdirs()
    new File(s"${_workspacePath}/tmp/job_$uniqueId").mkdirs()

    // 配置SpinalHDL编译器
    _spinalConfig.noAssertAtTimeZero = true
    val config = _spinalConfig.copy(targetDirectory = s"${_workspacePath}/tmp/job_$uniqueId").addTransformationPhase(new PhaseNetlist {
      override def impl(pc: PhaseContext): Unit = {
        // 确保顶层模块拉取其时钟域信号
        // 这对仿真访问时钟域信号很重要
        pc.topLevel.rework{
          val cd = pc.topLevel.clockDomain
          if(cd != null){
            if (cd.clock != null) cd.readClockWire
            if (cd.reset != null) cd.readResetWire
            if (cd.softReset != null) cd.readSoftResetWire
            if (cd.clockEnable != null) cd.readClockEnableWire
          }
        }

        // 实现SpinalSim白盒功能
        // 将标记为SpinalSimWb的BlackBox转换为普通组件
        pc.walkComponents{
          case b : BlackBox if b.isBlackBox && b.isSpinalSimWb => b.clearBlackBox()
          case _ =>
        }
      }
    })

    // 根据仿真后端类型生成HDL代码
    val report = _backend match {
      case SpinalSimBackendSel.VERILATOR => {
        config.addTransformationPhase(new SimVerilatorPhase)  // 添加Verilator专用阶段
        config.mode match {
          case spinal.core.SystemVerilog => config.generateSystemVerilog(rtl)
          case _ => config.generateVerilog(rtl)
        }
      }
      case SpinalSimBackendSel.GHDL => config.generateVhdl(rtl)
      case SpinalSimBackendSel.VCS | SpinalSimBackendSel.XSIM => config.generateVerilog(rtl)
      case SpinalSimBackendSel.IVERILOG => {
        config.mode match {
          case spinal.core.SystemVerilog => config.generateSystemVerilog(rtl)
          case _ => config.generateVerilog(rtl)
        }
      }
    }

    // 添加额外的RTL源文件和包含目录
    report.blackboxesSourcesPaths ++= _additionalRtlPath
    report.blackboxesIncludeDir ++= _additionalIncludeDir

    // 调用另一个compile方法进行后端编译
    compile[T](report)
  }

  /**
   * 编译已生成的SpinalReport为仿真对象
   *
   * 这个方法接受一个已经生成的SpinalReport（包含HDL代码和元数据），
   * 执行后端仿真器的编译工作，生成可执行的仿真对象。
   *
   * 与compile(rtl)的区别：
   * - compile(rtl): 从RTL代码生成器开始，包含HDL生成和后端编译两个阶段
   * - compile(report): 从已生成的HDL报告开始，只执行后端编译阶段
   *
   * @param report 包含HDL代码和元数据的SpinalReport
   * @return 编译好的仿真对象
   *
   * 使用场景：
   * 1. 当已经有现成的SpinalReport时（如从文件加载）
   * 2. 需要对同一个HDL代码使用不同仿真配置时
   * 3. 在编译流水线中分离HDL生成和仿真编译阶段时
   */
  def compile[T <: Component](report: SpinalReport[T]): SimCompiled[T] = {
    // 处理用户主目录路径
    if (_workspacePath.startsWith("~"))
      _workspacePath = System.getProperty("user.home") + _workspacePath.drop(1)

    // 设置工作空间名称（如果未指定则使用顶层模块名）
    if (_workspaceName == null)
      _workspaceName = s"${report.toplevelName}"

    // 分配唯一的工作空间名称（避免冲突）
    _workspaceName = SimWorkspace.allocateWorkspace(_workspacePath, _workspaceName)

    // 创建工作空间目录结构
    println(f"[Progress] Simulation workspace in ${new File(s"${_workspacePath}/${_workspaceName}").getAbsolutePath}")
    new File(s"${_workspacePath}").mkdirs()
    FileUtils.deleteQuietly(new File(s"${_workspacePath}/${_workspaceName}"))  // 清理旧的工作空间
    new File(s"${_workspacePath}/${_workspaceName}").mkdirs()
    new File(s"${_workspacePath}/${_workspaceName}/rtl").mkdirs()

    val compiledPath = new File(s"${_workspacePath}/${_workspaceName}")

    // 设置RTL目录和波形路径
    val rtlDir = new File(s"${_workspacePath}/${_workspaceName}/rtl")
    _testPath = _testPath.replace("$WORKSPACE", _workspacePath).replace("$COMPILED", _workspaceName)
    val wavePath = _testPath

    //    val rtlPath = rtlDir.getAbsolutePath
    report.generatedSourcesPaths.foreach { srcPath =>
      val src = new File(srcPath)
      val lines = Source.fromFile(src).getLines.toArray
      val w = new PrintWriter(src)
      for(line <- lines){
          val str = if(line.contains("readmem")){
            val exprPattern = """.*\$readmem.*\(\"(.+)\".+\).*""".r
            val absline = line match {
              case exprPattern(relpath) => {
                val windowsfix = relpath.replace(".\\", "")
                val abspath = new File(src.getParent + "/" + windowsfix).getAbsolutePath
                val ret = line.replace(relpath, abspath)
                ret.replace("\\", "\\\\") //windows escape "\"
              }
              case _ => new Exception("readmem abspath replace failed")
            }
            absline
          } else {
            line
          }
          w.println(str)
        }
      w.close()

      val dst = new File(rtlDir.getAbsolutePath + "/" + src.getName)
      FileUtils.copyFileToDirectory(src, rtlDir)
    }

    _backend match {
      case SpinalSimBackendSel.VERILATOR =>
        println(f"[Progress] Verilator compilation started")
        val startAt = System.nanoTime()

        // 创建Verilator后端配置
        // 包含所有必要的编译和仿真参数
        val vConfig = SpinalVerilatorBackendConfig[T](
          rtl = report,                                    // RTL报告
          waveFormat = _waveFormat,                        // 波形格式
          maxCacheEntries = _maxCacheEntries,              // 最大缓存条目数
          cachePath = if (!_disableCache) (if (_cachePath != null) _cachePath else s"${_workspacePath}/.cache") else null, // 缓存路径
          workspacePath = s"${_workspacePath}/${_workspaceName}", // 工作空间路径
          vcdPath = wavePath,                              // VCD文件路径
          vcdPrefix = _waveFilePrefix,                     // 波形文件前缀
          workspaceName = "verilator",                     // 工作空间名称
          waveDepth = _waveDepth,                          // 波形深度
          optimisationLevel = _optimisationLevel,          // 优化级别
          simulatorFlags = _simulatorFlags,                // 仿真器标志
          withCoverage = _withCoverage,                    // 是否启用覆盖率
          timePrecision = _timePrecision,                  // 时间精度
          testPath = _testPath,                            // 测试路径
          enableRtlAutoReset = _enableRtlAutoReset         // RTL分析自动复位功能开关
        )

        // 创建并初始化Verilator后端
        val backend = SpinalVerilatorBackend(vConfig)
        val deltaTime = (System.nanoTime() - startAt) * 1e-6
        println(f"[Progress] Verilator compilation done in $deltaTime%1.3f ms")

        // 返回编译好的仿真对象imCompiled实例，其中包含对backend的引用
        new SimCompiled(report, compiledPath, this){
          /**
           * Verilator后端的SimRaw实例创建
           *
           * 实现流程：
           * 1. 调用backend.instanciate()创建Verilator仿真实例
           *    - 生成唯一的仿真句柄
           *    - 加载编译好的动态链接库
           *    - 初始化C++仿真模型
           *    - 设置波形路径和随机种子
           * 2. 创建SimVerilator包装器，提供Scala接口
           * 3. 设置信号列表，用于信号访问验证
           *
           * @param name 测试名称，用于波形文件命名
           * @param seed 随机种子，传递给Verilator模型
           * @return SimVerilator实例，提供对Verilator模型的访问
           */

          /**
           * 重要：这个方法引用了外部的backend变量
           * Scala编译器会自动进行变量捕获，确保backend在整个SimCompiled实例的生命周期内都可用
           */
          override def newSimRaw(name: String, seed: Int): SimRaw = {
            // 创建SimVerilator实例，连接到Verilator后端,backend在这里仍然可用，即使compile方法已经返回
            val raw = new SimVerilator(backend, backend.instanciate(name, seed)) // 后续的doSim调用会使用这个backend
            raw.userData = backend.config.signals           // 设置信号列表
            raw
          }
        }

      case SpinalSimBackendSel.GHDL =>
        println(f"[Progress] GHDL compilation started")
        val startAt = System.nanoTime()
        val vConfig = SpinalGhdlBackendConfig[T](
          rtl = report,
          waveFormat = _waveFormat,
          workspacePath = s"${_workspacePath}/${_workspaceName}",
          wavePath = wavePath,
          wavePrefix = _waveFilePrefix,
          workspaceName = "ghdl",
          waveDepth = _waveDepth,
          optimisationLevel = _optimisationLevel,
          simulatorFlags = _simulatorFlags,
          runFlags = _runFlags,
          enableLogging = _withLogging,
          usePluginsCache = !_disableCache,
          timePrecision = _timePrecision,
          ghdlFlags = _ghdlFlags,
          testPath = _testPath
        )
        val backend = SpinalGhdlBackend(vConfig)
        val deltaTime = (System.nanoTime() - startAt) * 1e-6
        println(f"[Progress] GHDL compilation done in $deltaTime%1.3f ms")
        new SimCompiled(report, compiledPath, this){
          /**
           * GHDL后端的SimRaw实例创建
           *
           * GHDL使用VPI（Verilog Procedural Interface）进行仿真控制：
           * 1. 创建SimVpi实例，通过VPI接口连接到GHDL仿真器
           * 2. GHDL启动时会加载VPI插件，建立共享内存通信
           * 3. SimVpi通过SharedMemIface与GHDL进程通信
           * 4. 支持VHDL信号的读写和仿真控制
           *
           * 注意：GHDL的seed设置在后端初始化时完成，这里不需要传递
           *
           * @param name 测试名称，用于标识仿真实例
           * @param seed 随机种子（GHDL中通过其他方式设置）
           * @return SimVpi实例，提供对GHDL仿真器的VPI访问
           */
          override def newSimRaw(name: String, seed: Int): SimRaw = {
            val raw = new SimVpi(backend, name)              // 创建VPI接口实例
            raw.userData = backend.signals                   // 设置VHDL信号列表
            raw
          }
        }

      case SpinalSimBackendSel.IVERILOG =>
        println(f"[Progress] IVerilog compilation started")
        val startAt = System.nanoTime()
        val additionalFlags = {
          val stdConfigFlag = _simulatorFlags.find(_.startsWith("-g"))
          if (stdConfigFlag.isEmpty && this._spinalConfig.mode == spinal.core.SystemVerilog) {
            println(f"[Info] IVerilog set to use 2012 standard due to System Verilog being requested")
            Seq("-g2012")
          } else {
            Seq()
          }
        }

        val vConfig = SpinalIVerilogBackendConfig[T](
          rtl = report,
          waveFormat = _waveFormat,
          workspacePath = s"${_workspacePath}/${_workspaceName}",
          wavePath = s"${_workspacePath}/${_workspaceName}",
          wavePrefix = _waveFilePrefix,
          workspaceName = "iverilog",
          waveDepth = _waveDepth,
          optimisationLevel = _optimisationLevel,
          simulatorFlags = _simulatorFlags ++ additionalFlags,
          enableLogging = _withLogging,
          usePluginsCache = !_disableCache,
          timePrecision = _timePrecision,
          testPath = _testPath
        )
        val backend = SpinalIVerilogBackend(vConfig)
        val deltaTime = (System.nanoTime() - startAt) * 1e-6
        println(f"[Progress] IVerilog compilation done in $deltaTime%1.3f ms")
        new SimCompiled(report, compiledPath, this){
          /**
           * IVerilog后端的SimRaw实例创建
           *
           * IVerilog（Icarus Verilog）是开源的Verilog仿真器：
           * 1. 使用VPI接口进行仿真控制
           * 2. 通过vvp（Verilog VPI）运行时执行仿真
           * 3. 支持SystemVerilog 2012标准（通过-g2012标志）
           * 4. 使用SharedMemIface进行进程间通信
           *
           * @param name 测试名称，用于标识仿真实例
           * @param seed 随机种子（通过VPI设置）
           * @return SimVpi实例，提供对IVerilog仿真器的VPI访问
           */
          override def newSimRaw(name: String, seed: Int): SimRaw = {
            val raw = new SimVpi(backend, name)              // 创建VPI接口实例
            raw.userData = backend.signals                   // 设置Verilog信号列表
            raw
          }
        }

      case SpinalSimBackendSel.VCS =>
        val vConfig = SpinalVCSBackendConfig[T](
          rtl = report,
          waveFormat = _waveFormat,
          workspacePath = s"${_workspacePath}/${_workspaceName}",
          wavePath = s"${_workspacePath}/${_workspaceName}",
          wavePrefix = _waveFilePrefix,
          workspaceName = "vcs",
          waveDepth = _waveDepth,
          optimisationLevel = _optimisationLevel,
          simulatorFlags = _simulatorFlags,
          enableLogging = _withLogging,
          usePluginsCache = !_disableCache,
          vcsCC = _vcsCC,
          vcsLd = _vcsLd,
          vcsFlags = _vcsUserFlags,
          simSetupFile = _vcsSimSetupFile,
          envSetup = _vcsEnvSetup,
          timePrecision = _timePrecision
        )
        val backend = SpinalVCSBackend(vConfig)
        new SimCompiled(report, compiledPath, this) {
          /**
           * VCS后端的SimRaw实例创建
           *
           * VCS（Synopsys VCS）是商业级Verilog/SystemVerilog仿真器：
           * 1. 高性能的编译型仿真器
           * 2. 使用VPI接口进行仿真控制
           * 3. 支持完整的SystemVerilog标准
           * 4. 提供高级调试和分析功能
           * 5. 通过SharedMemIface进行高效的进程间通信
           *
           * @param name 测试名称，用于标识仿真实例
           * @param seed 随机种子（通过VPI设置）
           * @return SimVpi实例，提供对VCS仿真器的VPI访问
           */
          override def newSimRaw(name: String, seed: Int): SimRaw = {
            val raw = new SimVpi(backend, name)              // 创建VPI接口实例
            raw.userData = backend.signals                   // 设置信号列表
            raw
          }
        }

      case SpinalSimBackendSel.XSIM =>
        println(f"[Progress] XSIM compilation started")
        val vConfig = SpinalXSimBackendConfig[T](
          rtl = report,
          waveFormat = _waveFormat,
          workspacePath = s"${_workspacePath}/${_workspaceName}",
          wavePath = s"${_workspacePath}/${_workspaceName}",
          workspaceName = "xsim",
          xciSourcesPaths = _xciSourcesPaths,
          bdSourcesPaths = _bdSourcesPaths,
          xilinxDevice = _xilinxDevice,
          simScript = _simScript,
          simulatorFlags = _simulatorFlags,
          timePrecision = _timePrecision
        )
        val backend = SpinalXSimBackend(vConfig)
        new SimCompiled(report, compiledPath, this) {
          /**
           * XSIM后端的SimRaw实例创建
           *
           * XSIM（Xilinx Vivado Simulator）是Xilinx的仿真器：
           * 1. 专为Xilinx FPGA设计优化
           * 2. 支持SystemVerilog和VHDL混合仿真
           * 3. 集成在Vivado设计套件中
           * 4. 支持Xilinx IP核和原语的精确仿真
           * 5. 使用专用的SimXSim接口（不是VPI）
           *
           * 特点：
           * - 支持.xci IP核文件
           * - 支持.bd块设计文件
           * - 可指定Xilinx器件型号进行精确仿真
           * - 支持自定义仿真脚本
           *
           * @param name 测试名称（XSIM中seed通过其他方式设置）
           * @param seed 随机种子（在XSIM后端中处理）
           * @return SimXSim实例，提供对XSIM仿真器的专用访问
           */
          override def newSimRaw(name: String, seed: Int): SimRaw = {
            val raw = new SimXSim(backend)                   // 创建XSIM专用接口实例
            raw.userData = backend.signals                   // 设置信号列表
            raw
          }
        }
    }
  }
}


/**
  * Legacy simulation configuration
  */
case class SimConfigLegacy[T <: Component](
  var _rtlGen       : Option[() => T] = None,
  var _spinalConfig : SpinalConfig = SpinalConfig(),
  var _spinalReport : Option[SpinalReport[T]] = None
){

  private val _simConfig = SpinalSimConfig()

  def withWave: this.type = { _simConfig.withWave; this }
  def withWave(depth: Int): this.type = { _simConfig.withWave(depth); this }

  def workspacePath(path: String): this.type = { _simConfig.workspacePath(path); this }
  def workspaceName(name: String): this.type = { _simConfig.workspaceName(name); this }

  def withConfig(config: SpinalConfig): this.type =  { _simConfig.withConfig(config); this }

  def noOptimisation: this.type     = { _simConfig.noOptimisation ; this }
  def fewOptimisation: this.type    = { _simConfig.fewOptimisation ; this }
  def normalOptimisation: this.type = { _simConfig.normalOptimisation ; this }
  def allOptimisation: this.type    = { _simConfig.allOptimisation ; this }

  def doSim(body: T => Unit): Unit = compile().doSim(body)
  def doSim(name: String)(body: T => Unit): Unit = compile().doSim(name)(body)
  def doSim(name: String, seed: Int)(body: T => Unit): Unit = compile().doSim(name, seed)(body)

  def doManagedSim(body: T => Unit): Unit = compile().doSim(body)
  def doManagedSim(name: String)(body: T => Unit): Unit = compile().doSim(name)(body)
  def doManagedSim(name: String, seed: Int)(body: T => Unit): Unit = compile().doSim(name, seed)(body)

  def doSimUntilVoid(body: T => Unit): Unit = compile().doSimUntilVoid(body)
  def doSimUntilVoid(name: String)(body: T => Unit): Unit = compile().doSimUntilVoid(name)(body)
  def doSimUntilVoid(name: String, seed: Int)(body: T => Unit): Unit = compile().doSimUntilVoid(name, seed)(body)

  def compile(): SimCompiled[T] = {
    (_rtlGen, _spinalReport)  match {
      case (None, Some(report)) => _simConfig.compile(report)
      case (Some(gen), None)    => _simConfig.compile(gen())
      case _ => ???
    }
  }
}
