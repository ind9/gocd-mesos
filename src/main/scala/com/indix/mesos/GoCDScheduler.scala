package com.indix.mesos

import java.util.UUID

import com.typesafe.config.ConfigFactory
import org.apache.mesos.Protos.ContainerInfo.DockerInfo
import org.apache.mesos.Protos.Environment.Variable
import org.apache.mesos.Protos._
import org.apache.mesos.{MesosSchedulerDriver, Protos, Scheduler, SchedulerDriver}

import scala.collection.JavaConverters._


class GoCDScheduler(conf : FrameworkConfig) extends Scheduler {


  lazy val envForGoCDTask = Environment.newBuilder()
    .addVariables(Variable.newBuilder().setName("GOCD_SERVER").setValue(conf.goMasterServer).build())
    .addVariables(Variable.newBuilder().setName("REPO_USER").setValue(conf.goUserName).build())
    .addVariables(Variable.newBuilder().setName("REPO_PASSWD").setValue(conf.goPassword).build())
    .addVariables(Variable.newBuilder().setName("AGENT_PACKAGE_URL").setValue(conf.goAgentBinary).build())

  override def error(driver: SchedulerDriver, message: String) {}

  override def executorLost(driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID, status: Int) {
    println(s"executor completed execution with status: $status")
  }

  override def slaveLost(driver: SchedulerDriver, slaveId: SlaveID) {}

  override def disconnected(driver: SchedulerDriver): Unit = {
    println(s"Received Disconnected message $driver")
  }

  override def frameworkMessage(driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID, data: Array[Byte]) {}

  override def statusUpdate(driver: SchedulerDriver, status: TaskStatus) {
    println(s"received status update $status")
  }

  override def offerRescinded(driver: SchedulerDriver, offerId: OfferID) {}

  /**
   *
   * This callback is called when resources are available to  run tasks
   *
   */
  override def resourceOffers(driver: SchedulerDriver, offers: java.util.List[Offer]) {
    println(s"Received resource offer size: ${offers.size()}")
    //for every available offer run tasks
    for (offer <- offers.asScala) {
      println(s"offer $offer")
      val nextTask = TaskQueue.dequeue
      if(nextTask != null) {
        val task = deployGoAgentTask(nextTask, offer)
        task match {
          case Some(tt) => driver.launchTasks(List(offer.getId).asJava, List(tt).asJava)
          case None => {
            TaskQueue.enqueue(nextTask)
            println(s"declining unused offer because offer is not enough")
            driver.declineOffer(offer.getId)
          }
        }
      }
      else {
        println(s"declining unused offer because there is no task")
        driver.declineOffer(offer.getId)
      }
    }
  }

  def resource(name: String, value: Double) = {
    Resource.newBuilder()
      .setType(Protos.Value.Type.SCALAR)
      .setName(name)
      .setScalar(Value.Scalar.newBuilder().setValue(value))
      .build
  }

  def dockerContainer(image: String) = {
    ContainerInfo.newBuilder()
      .setType(ContainerInfo.Type.DOCKER)
      .setDocker(DockerInfo.newBuilder().setImage(image).build)
      .build
  }
  
  def deployGoAgentTask(goTask: GoTask, offer: Offer) =  {
    val needed = Resources(goTask)
    val available = Resources(offer)
    if(available.canSatisfy(needed)) {
      val id = "task" + System.currentTimeMillis()
      val taskProperties = envForGoCDTask.addVariables(Variable.newBuilder().setName("GUID").setValue(UUID.randomUUID().toString).build())
      val task = TaskInfo.newBuilder
        .setCommand(
          CommandInfo
            .newBuilder()
            .addUris(CommandInfo.URI.newBuilder().setValue(goTask.uri).setExecutable(true))
            .setValue(goTask.cmdString)
            .setEnvironment(taskProperties)
            .build)
        .setName(id)
        .setTaskId(TaskID.newBuilder.setValue(id))

       if(goTask.dockerImage.nonEmpty)
        task.setContainer(dockerContainer(goTask.dockerImage))

       task
        .addResources(resource("cpus", needed.cpus))
        .addResources(resource("mem", needed.memory))
        .setSlaveId(offer.getSlaveId)
      Some(task.build())
    } else {
      None
    }
  }

  override def reregistered(driver: SchedulerDriver, masterInfo: MasterInfo) {
    println(s"RE-registered with mesos master.")
  }

  override def registered(driver: SchedulerDriver, frameworkId: FrameworkID, masterInfo: MasterInfo) {
    println(s"registered with mesos master. Framework id is ${frameworkId.getValue}")
  }

}


  object GoCDMesosFramework extends App {
   val config = new FrameworkConfig(ConfigFactory.load())

    val id = "GOCD-Mesos-" + System.currentTimeMillis()


    val frameworkInfo = FrameworkInfo.newBuilder()
      .setId(FrameworkID.newBuilder().setValue(id).build())
      .setName("GOCD-Mesos")
      .setUser("")
      .setRole("*")
      .setCheckpoint(false)
      .setFailoverTimeout(0.0d)
      .build()

  val poller = GOCDPoller(config.goMasterServer, config.goUserName, config.goPassword)
  val timeInterval = 1000
  val runnable = new Runnable {
    override def run(): Unit = {
      while(true) {
        poller.pollAndAddTask
        Thread.sleep(timeInterval)
      }
    }
  }
  val thread = new Thread(runnable)
  thread.start()
    val scheduler = new GoCDScheduler(config)
    val driver = new MesosSchedulerDriver(scheduler, frameworkInfo, config.mesosMaster)
    driver.run()
    println("Started!!!")
}