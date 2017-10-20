package org.monitor.app;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.Device;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.PortStatistics;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.StoredFlowEntry;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.statistic.FlowEntryWithLoad;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.statistic.SummaryFlowEntryWithLoad;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.statistic.FlowStatisticService;
import org.onosproject.net.host.HostService;
import org.onosproject.net.flow.criteria.Criterion;
import org.onlab.packet.MacAddress;
import org.onlab.packet.IpAddress;
import org.onosproject.net.Host;
import java.util.Set;

import org.onosproject.net.MastershipRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Timer;

import org.json.JSONObject;
import org.json.JSONArray;
import java.text.DateFormat;
import java.util.Date;
import java.util.SimpleTimeZone;
import java.util.TimeZone;
import java.text.SimpleDateFormat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLConnection;



@Component(immediate = true)
public class AppComponent {
	private final Logger log = LoggerFactory.getLogger(getClass());

	@Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
	protected CoreService coreService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
	protected DeviceService deviceService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
	protected FlowStatisticService flowStatsService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
	protected HostService hostService;

	private DeviceListener deviceListener;
	private ApplicationId appId;

	@Activate
	protected void activate() {
		log.info("Inside Activate");
		appId = coreService.registerApplication("org.monitor.app");
		deviceListener = new InnerDeviceListener();
		deviceService.addListener(deviceListener); // adding a device event listener (mainly for port statistics update event)
		log.info("Started");

	}

	@Deactivate
	protected void deactivate() {
		deviceService.removeListener(deviceListener); // removing the listener
		log.info("Stopped");
	}

	public class InnerDeviceListener implements DeviceListener, Runnable { // Runnable to make it multi-threaded
		private DeviceEvent ev;
		public InnerDeviceListener(DeviceEvent event) { // for the purpose of thread creation using event
		       this.ev = event;
		   }
		public InnerDeviceListener() { // empty contructor so that event listening is not affected
				this.ev = null;
		   }
		@Override
		public void event(DeviceEvent event) {
			Thread thread = new Thread(new InnerDeviceListener(event)); // event taken care of by new thread
			thread.start();
		}
		@Override
		public void run(){
			process(ev);
		}

	private void process(DeviceEvent event) { // method to collect all the statistics
			//log.info("Received event:" + event.type());
			FlowEntry.FlowLiveType inLiveType = null; // =
														// FlowEntry.FlowLiveType.LONG;
			Instruction.Type inInstructionType = null;
			switch (event.type()) {
			case PORT_STATS_UPDATED:
				if (deviceService.getRole(event.subject().id()) == MastershipRole.MASTER) {
					DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
					Date dateobj = new Date();

					String lv_dateFormateInUTC = ""; // Will hold the final
														// converted date
					SimpleDateFormat lv_formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
					lv_formatter.setTimeZone(TimeZone.getTimeZone("UTC")); // Elasticsearch assumes the date time to be in UTC
					lv_dateFormateInUTC = lv_formatter.format(dateobj);

					Device device = event.subject();
					//log.info("--------------------------------------------------");
					//log.info("Port Stats updated for Device : {}", device.id());
					List<Port> ports = deviceService.getPorts(device.id()); // getting all ports of the device
					for (Port port : ports) {
						if (!port.number().isLogical()) {
							try {
								JSONObject portStatistics = new JSONObject(); // JSON object for port statistics data injection into Elasticsearch
								portStatistics.put("Device_ID", device.id());
								portStatistics.put("Time", lv_dateFormateInUTC); 
								portStatistics.put("PortNumber", port.number());
								JSONArray statisticsArray = new JSONArray();

								//log.info("Getting info for port" + port.number());
								PortStatistics portstat = deviceService.getStatisticsForPort(device.id(),
										port.number()); // port statistics for particular port of the device
								PortStatistics portdeltastat = deviceService.getDeltaStatisticsForPort(device.id(),
										port.number()); // port delta statistics for particular port of the device
								if (portstat != null) {
									JSONObject portstatobject = new JSONObject();
									portStatistics.put("PortstatBytesReceived", portstat.bytesReceived());
									portStatistics.put("PortstatBytesSent", portstat.bytesSent());
									portStatistics.put("PortstatPacketsReceived", portstat.packetsReceived());
									portStatistics.put("PortstatPacketsSent", portstat.packetsSent());

									//log.info("***************************************************");
									//log.info("port Statistics {} Json", portStatistics.toString());
									//log.info("***************************************************");

									sendPost("http://127.0.0.1:9200/portstats/portstatistics",
											portStatistics.toString()); // sending portstatistics to Elasticsearch

									List<FlowEntryWithLoad> typedFlowLoad = flowStatsService.loadAllByType(device,
											port.number(), inLiveType, inInstructionType);
									if (typedFlowLoad != null) {
										ConnectPoint cp = new ConnectPoint(device.id(), port.number());
										// printPortFlowsLoad(cp,
										// typedFlowLoad);
										//log.info("deviceId/Port={}/{}, {} flows", cp.elementId(), cp.port(),
												//typedFlowLoad.size());

										for (FlowEntryWithLoad fel : typedFlowLoad) { // getting all flows and statistics
											StoredFlowEntry sfe = fel.storedFlowEntry();
											JSONObject flowStatistics = new JSONObject();

											Criterion src = sfe.selector().getCriterion(Criterion.Type.ETH_SRC);
											Criterion dst = sfe.selector().getCriterion(Criterion.Type.ETH_DST);
											if (!(src == null || dst == null)) {
												String srcString = src.toString();
												srcString = srcString.replace("ETH_SRC:", "");
												String dstString = dst.toString();
												dstString = dstString.replace("ETH_DST:", "");
												// log.info("Selection Criteria
												// {}",
												// inport.type().toString());
												MacAddress sourceMac = MacAddress.valueOf(srcString);
												MacAddress destinationMac = MacAddress.valueOf(dstString);
												IpAddress srcIp = hostService.getHostsByMac(sourceMac).iterator().next()
														.ipAddresses().iterator().next();
												IpAddress dstIp = hostService.getHostsByMac(destinationMac).iterator()
														.next().ipAddresses().iterator().next();
												//log.info("Source IP {}", srcIp.toString());
												//log.info("Destination IP {}", dstIp.toString());

												flowStatistics.put("Device_ID", device.id());
												flowStatistics.put("Time", lv_dateFormateInUTC);
												flowStatistics.put("PortNumber", port.number());
												flowStatistics.put("FlowID", Long.toHexString(sfe.id().value()));
												flowStatistics.put("Source_IP", srcIp);
												flowStatistics.put("Destination_IP", dstIp);
												flowStatistics.put("PacketsSeenByFlowRule", sfe.packets());
												flowStatistics.put("BytesSeenByFlowRule", sfe.bytes());
												//log.info("***************************************************");
												//log.info("Flow Statistics {} Json", flowStatistics);
												//log.info("***************************************************");
												sendPost("http://127.0.0.1:9200/flowstats/flowstatistics",
														flowStatistics.toString());
											}

										}

									}
								} else
									log.info("Unable to read portStats");
								if (portdeltastat != null) {

									JSONObject portDeltaStatistics = new JSONObject(); // port delta statistics JSON
									portDeltaStatistics.put("Device_ID", device.id());
									portDeltaStatistics.put("Time", lv_dateFormateInUTC);
									portDeltaStatistics.put("PortNumber", port.number());
									portDeltaStatistics.put("PortDeltaStatBytesReceived",
											portdeltastat.bytesReceived());
									portDeltaStatistics.put("PortDeltaStatBytesSent", portdeltastat.bytesSent());
									portDeltaStatistics.put("PortDeltaStatPacketsReceived",
											portdeltastat.packetsReceived());
									portDeltaStatistics.put("PortDeltaStatPacketsSent", portdeltastat.packetsSent());

									//log.info("***************************************************");
									//log.info("Port Delta Statistics {} Json", portDeltaStatistics);
									//log.info("***************************************************");
									sendPost("http://127.0.0.1:9200/portdeltastats/portdeltastatistics",
											portDeltaStatistics.toString());

								} else
									log.info("Unable to read portDeltaStats");
							} catch (Exception ex) {
								log.info("Exception {}", ex);
							}
						}
					}
				}
				break;
			default:
				break;
		}
			
	}

	private String sendPost(String url, String param) throws Exception { // method to send data to Elasticsearch
		PrintWriter out = null;
		BufferedReader in = null;
		String result = "";
		try {
			URL realUrl = new URL(url);
			URLConnection conn = realUrl.openConnection();
			conn.setConnectTimeout(5000);
			conn.setReadTimeout(10 * 1000);
			conn.setDoOutput(true);
			conn.setDoInput(true);
			out = new PrintWriter(conn.getOutputStream());
			out.print(param);
			out.flush();
			in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
			String line;
			while ((line = in.readLine()) != null) {
				result += line;
			}
		} catch (Exception e) {
			log.info("Exception {} ", e);
			throw e;
		} finally {
			try {
				if (out != null)
					out.close();
				if (in != null)
					in.close();
			} catch (Exception ex) {
			}
		}
		return result;
	}
}}

