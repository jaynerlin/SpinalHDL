/**
 * 仿真线程管理和调度实现文件
 *
 * 文件作用：
 * 实现SpinalHDL仿真系统中的协作式多线程调度机制。每个SimThread代表一个
 * 仿真执行上下文，支持时间感知的线程调度和同步原语。
 *
 * 在仿真流程中的位置：
 * 用户仿真代码 -> SimThread -> JVM线程 -> SimManager调度器 -> 仿真器推进
 *
 * 核心功能：
 * 1. 线程生命周期管理：
 *    - 创建和销毁仿真线程
 *    - 管理线程状态和异常处理
 *    - 提供线程间同步机制
 *
 * 2. 仿真时间调度：
 *    - sleep(): 按仿真时间挂起线程
 *    - waitUntil(): 等待条件满足
 *    - join(): 等待其他线程完成
 *
 * 3. 协作式调度：
 *    - 通过异常机制实现线程切换
 *    - 支持非抢占式的协作调度
 *    - 维护仿真时间的一致性
 *
 * 设计特点：
 * - 每个SimThread对应一个真实的JVM线程
 * - 使用异常机制实现轻量级的线程调度
 * - 支持fork创建并发的仿真线程
 * - 提供完整的线程同步和通信机制
 */

package spinal.sim


import scala.collection.mutable.ArrayBuffer
import scala.util.Random

/**
 * 仿真线程取消调度异常
 * 当仿真线程需要被取消调度时抛出此异常
 */
case class SimThreadUnschedule() extends Exception

/**
 * 仿真线程类
 *
 * 这是SpinalHDL仿真中的核心执行单元，负责：
 * 1. 执行用户的仿真逻辑函数（doSim中的body参数）
 * 2. 管理仿真线程的生命周期和调度
 * 3. 提供仿真控制原语（sleep、waitUntil、fork等）
 * 4. 处理线程间的同步和通信
 *
 * 关键设计：
 * - 每个SimThread对应一个JVM线程
 * - 通过SimManager进行统一调度
 * - 支持协作式多任务（通过suspend/resume）
 * - 提供仿真时间感知的调度机制
 *
 * @param body 仿真逻辑函数，这就是doSim()中用户传入的仿真代码
 */
class SimThread(body: => Unit) {
  private val manager = SimManagerContext.current.manager    // 获取当前仿真管理器
  var waitingThreads = ArrayBuffer[() => Unit]()             // 等待此线程完成的其他线程列表

  val mainContext = SimManagerContext.current                // 保存主仿真上下文
  var exception : Throwable = null                           // 存储线程执行中的异常
  /**
   * 等待此线程完成
   *
   * 当前线程会被挂起，直到此SimThread执行完成。
   * 这是实现线程同步的关键机制。
   */
  def join(): Unit = {
    val thread = SimManagerContext.current.thread
    assert(thread != this)                                   // 不能自己等待自己
    if (!this.isDone) {
      waitingThreads += thread.managerResume                 // 将当前线程加入等待列表
      thread.suspend()                                       // 挂起当前线程
    }
  }

  /**
   * 仿真时间睡眠
   *
   * 让当前线程睡眠指定的仿真周期数。
   * 这是仿真中最常用的时间控制原语。
   *
   * @param cycles 要睡眠的仿真周期数
   */
  def sleep(cycles: Long): Unit = {
    manager.schedule(cycles, this)                           // 在指定周期后重新调度此线程
    suspend()                                                // 挂起当前线程
  }

  /**
   * 等待条件满足
   *
   * 挂起当前线程，直到指定条件为真。
   * 这是实现信号等待的核心机制。
   *
   * @param cond 要等待的条件（按名传递，每次检查时重新评估）
   */
  def waitUntil(cond: => Boolean): Unit = {
    if (!cond) {
      // 注册敏感性监听器，在每个仿真周期检查条件
      manager.sensitivities += new SimManagerSensitive {
        override def update() = {
          Thread.currentThread()
          if (cond || isDone) {
            manager.schedule(0, SimThread.this)              // 条件满足，立即重新调度
            false                                            // 移除此敏感性
          } else {
            true                                             // 保持此敏感性
          }
        }
      }
      suspend()                                              // 挂起当前线程
    }
  }

  def isDone: Boolean  = done
  def nonDone: Boolean = !done

  def suspend(): Unit = {
    manager.context.thread = null
    jvmThread.barrier.await()
    jvmThread.park()
    manager.context.thread = SimThread.this
    if(isDone) {
      throw SimThreadUnschedule()
    }
  }

  def resume(): Unit = {
    SimManagerContext.current.manager.schedule(0)(this.managerResume())
  }

  //Should only be used from the sim manager itself, not from a simulation thread
  def managerResume() = {
    jvmThread.unpark()
    jvmThread.barrier.await()
    if (isDone) {
      if(exception != null) throw exception
      waitingThreads.foreach(thread => {
        SimManagerContext.current.manager.schedule(0)(thread())
      })
    }
  }

  var done = false
  def terminate(): Unit ={
    if(manager.context.thread == this) {
      throw SimThreadUnschedule()
    } else {
      done = true
    }
  }

  val spawnTask = manager.newSpawnTask()                     // 创建线程生成任务

  /**
   * JVM线程执行体 - 这里是仿真逻辑函数的实际执行位置
   *
   * 这是SimThread的核心：一个真正的JVM线程，负责执行用户的仿真代码。
   *
   * 执行流程：
   * 1. 设置JVM线程环境（线程本地变量、上下文等）
   * 2. 执行spawnTask.setup()进行初始化
   * 3. 执行body - 这就是doSim()中用户传入的仿真逻辑函数！
   * 4. 处理各种异常情况
   * 5. 清理线程状态
   *
   * 关键点：
   * - body参数就是用户在doSim { dut => ... }中编写的仿真代码
   * - 通过SimManagerContext.threadLocal维护线程本地状态
   * - 支持协作式调度（通过异常机制实现suspend/resume）
   * - 异常处理确保线程状态的正确清理
   */
  val jvmThread = manager.newJvmThread {
    // 1. 设置JVM线程环境
    manager.setupJvmThread(Thread.currentThread())          // 配置线程管理器
    SimManagerContext.threadLocal.set(mainContext)          // 设置线程本地上下文
    manager.context.thread = SimThread.this                 // 将当前SimThread设为活动线程

    try {
      // 2. 执行初始化任务
      spawnTask.setup()

      // 3. 执行用户仿真逻辑 - 这就是doSim()的body参数！
      body                                                   // ← 用户的仿真代码在这里执行

    } catch {
      // 4. 异常处理
      case e : JvmThreadUnschedule =>                        // JVM线程取消调度
        manager.context.thread = null
        done = true
        throw e
      case e : SimThreadUnschedule =>                        // 仿真线程取消调度（正常的suspend/resume机制）
      case e : Throwable =>                                  // 其他异常（用户代码错误等）
        exception = e                                        // 保存异常，在join时重新抛出
    }

    // 5. 清理线程状态
    manager.context.thread = null                           // 清除活动线程引用
    done = true                                             // 标记线程完成
  }
}

trait SimThreadSpawnTask{
  def setup(): Unit
}