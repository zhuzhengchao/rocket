package rocket

import Chisel._
import Node._
import Constants._
import uncore._
import Util._

case class RocketConfiguration(lnConf: LogicalNetworkConfiguration, co: CoherencePolicyWithUncached,
                               icache: ICacheConfig, dcache: DCacheConfig,
                               fpu: Boolean, vec: Boolean,
                               fastLoadWord: Boolean = true,
                               fastLoadByte: Boolean = false,
                               fastMulDiv: Boolean = true)
{
  val dcacheReqTagBits = 9 // enforce compliance with require()
  val xprlen = 64
  val nxpr = 32
  val nxprbits = log2Up(nxpr)
  val rvc = false
  if (fastLoadByte) require(fastLoadWord)
}

class Tile(resetSignal: Bool = null)(confIn: RocketConfiguration) extends Component(resetSignal) with ClientCoherenceAgent
{
  val memPorts = 2 + confIn.vec
  val dcachePortID = 0
  implicit val dcConf = confIn.dcache.copy(reqtagbits = confIn.dcacheReqTagBits + log2Up(memPorts), databits = confIn.xprlen)
  implicit val lnConf = confIn.lnConf
  implicit val conf = confIn.copy(dcache = dcConf)

  val io = new Bundle {
    val tilelink = new TileLinkIO
    val host = new HTIFIO(lnConf.nClients)
  }

  val core      = new Core
  val icache    = new Frontend()(confIn.icache, lnConf)
  val dcache    = new HellaCache

  val arbiter   = new MemArbiter(memPorts)
  arbiter.io.requestor(dcachePortID) <> dcache.io.mem
  arbiter.io.requestor(1) <> icache.io.mem

  io.tilelink.acquire <> arbiter.io.mem.acquire
  io.tilelink.acquire_data <> dcache.io.mem.acquire_data
  arbiter.io.mem.grant <> io.tilelink.grant
  io.tilelink.grant_ack <> arbiter.io.mem.grant_ack
  dcache.io.mem.probe <> io.tilelink.probe
  io.tilelink.release_data <> dcache.io.mem.release_data
  io.tilelink.release.valid   := dcache.io.mem.release.valid
  dcache.io.mem.release.ready := io.tilelink.release.ready
  io.tilelink.release.bits := dcache.io.mem.release.bits
  io.tilelink.release.bits.payload.client_xact_id :=  Cat(dcache.io.mem.release.bits.payload.client_xact_id, UFix(dcachePortID, log2Up(memPorts))) // Mimic client id extension done by MemArbiter for Acquires from either cache)

  if (conf.vec) {
    val vicache = new Frontend()(ICacheConfig(128, 1, conf.co), lnConf) // 128 sets x 1 ways (8KB)
    arbiter.io.requestor(2) <> vicache.io.mem
    core.io.vimem <> vicache.io.cpu
  }

  core.io.host <> io.host
  core.io.imem <> icache.io.cpu
  core.io.dmem <> dcache.io.cpu
}
