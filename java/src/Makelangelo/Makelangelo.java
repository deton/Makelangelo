package Makelangelo;
/**@(#)drawbotGUI.java
 *
 * drawbot application with GUI
 *
 * @author Dan Royer (dan@marginallyclever.com)
 * @version 1.00 2012/2/28
 */


// io functions
import jssc.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.prefs.Preferences;

import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;

import org.kabeja.dxf.*;
import org.kabeja.dxf.helpers.*;
import org.kabeja.parser.*;

import DrawingTools.DrawingTool;
import Filters.*;


// TODO while not drawing, in-app gcode editing with immediate visual feedback ?
// TODO image processing options - cutoff, exposure, resolution, voronoi stippling, edge tracing ?
// TODO vector output ?

public class Makelangelo
		extends JPanel
		implements ActionListener, KeyListener, SerialPortEventListener
{
	// Java required?
	static final long serialVersionUID=1L;

	// software version
	static final String version="6";
	
	private static Makelangelo singletonObject;
	
	// Image processing
		// TODO use a ServiceLoader for plugins
		Filter [] image_converters;
		String [] filter_names = null;
		boolean startConvertingNow;
	
	
	// Serial connection
		// TODO put all serial stuff in a SerialConnection (Connection) class, hide it inside Robot class?
		private static final int BAUD_RATE = 57600;
		//private CommPortIdentifier portIdentifier;
		//private CommPort commPort;
		private SerialPort serialPort;
		private String[] portsDetected;
		private boolean portOpened=false;
		private boolean portConfirmed=false;
		// Serial communication
		static private final String cue = "> ";
		static private final String hello = "HELLO WORLD! I AM DRAWBOT #";
		static private final String nochecksum = "NOCHECKSUM";
		static private final String badchecksum = "BADCHECKSUM ";
		static private final String badlinenum = "BADLINENUM ";
	
	// parsing input from Makelangelo
	private String serial_recv_buffer="";
	
	private Preferences prefs = Preferences.userRoot().node("DrawBot");
	private String[] recentFiles;
	private String recentPort;
	//private boolean allowMetrics=true;
		
	// machine settings while running
	private double feed_rate;
	private boolean penIsUp,penIsUpBeforePause;
	
	// GUI elements
	private static JFrame mainframe;
	private JMenuBar menuBar;
    private JMenuItem buttonOpenFile, buttonHilbertCurve, buttonText2GCODE, buttonSaveFile;
    private JMenuItem buttonExit;
    private JMenuItem buttonAdjustSounds, buttonAdjustGraphics, buttonAdjustLanguage;
    private JMenuItem buttonLoadMachineConfig, buttonAdjustMachineSize, buttonAdjustPulleySize, buttonJogMotors, buttonChangeTool, buttonAdjustTool;
    private JMenuItem buttonRescan, buttonDisconnect;
    private JMenuItem buttonStart, buttonStartAt, buttonPause, buttonHalt;
    private JMenuItem buttonZoomIn,buttonZoomOut,buttonZoomToFit;
    private JMenuItem buttonAbout,buttonCheckForUpdate;
    
    private JMenuItem [] buttonRecent = new JMenuItem[10];
    private JMenuItem [] buttonPorts;

    public boolean dialog_result=false;
    
    // logging
    private JTextPane log;
    private JScrollPane logPane;
    HTMLEditorKit kit;
    HTMLDocument doc;
    PrintWriter logToFile;
    
    // panels
    private DrawPanel previewPane;
	private StatusBar statusBar;
	private JPanel drivePane;
	
	// command line
	private JPanel textInputArea;
	private JTextField commandLineText;
	private JButton commandLineSend;
	
	// prevent repeating pings from appearing in console
	boolean lastLineWasCue=false;

	// reading file
	private boolean running=false;
	private boolean paused=true;
	
	GCodeFile gcode = new GCodeFile();
	
	

	private void RaisePen() {
		SendLineToRobot("G00 Z"+MachineConfiguration.getSingleton().getPenUpString());
		penIsUp=true;
	}
	
	private void LowerPen() {
		SendLineToRobot("G00 Z"+MachineConfiguration.getSingleton().getPenDownString());
		penIsUp=false;
	}
	
	private Makelangelo() {
		StartLog();
		MachineConfiguration.getSingleton();
        GetRecentFiles();
        GetRecentPort();
        LoadImageConverters();
	}
	
	// TODO use a serviceLoader instead
	protected void LoadImageConverters() {
		image_converters = new Filter[7];  // this number must match the actual number of filters.
		int i=0;
		image_converters[i++] = new Filter_GeneratorTSP();
		image_converters[i++] = new Filter_GeneratorSpiral();
		image_converters[i++] = new Filter_GeneratorCrosshatch();
		image_converters[i++] = new Filter_GeneratorScanline();
		image_converters[i++] = new Filter_GeneratorPulse();
		image_converters[i++] = new Filter_GeneratorBoxes();
		image_converters[i++] = new Filter_GeneratorRGB();
		
		filter_names = new String[image_converters.length];
		for(i=0;i<image_converters.length;++i) {
			filter_names[i] = image_converters[i].GetName();
		}
	}
	
	protected void finalize() throws Throwable {
		//do finalization here
		EndLog();
		super.finalize(); //not necessary if extending Object.
	} 


	@Override
	public void keyTyped(KeyEvent e) {}

    /** Handle the key-pressed event from the text field. */
    public void keyPressed(KeyEvent e) {}

    /** Handle the key-released event from the text field. */
    public void keyReleased(KeyEvent e) {
		if(e.getKeyCode() == KeyEvent.VK_ENTER) {
			if(portConfirmed && !running) {
				ProcessLine(commandLineText.getText());
				commandLineText.setText("");
			}
		}
	}
	
	private void StartLog() {
		try {
			logToFile = new PrintWriter(new FileWriter("log.html"));
			Calendar cal = Calendar.getInstance();
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			logToFile.write("<h3>"+sdf.format(cal.getTime())+"</h3>\n");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void EndLog() {
		logToFile.close();
	}
	
	public static Makelangelo getSingleton() {
		if(singletonObject==null) {
			singletonObject = new Makelangelo();
		}
		return singletonObject;
	}
	
	//  data access
	public ArrayList<String> getGcode() {
		return gcode.lines;
	}

	private void PlaySound(String url) {
		if(url.isEmpty()) return;
		
		try {
			Clip clip = AudioSystem.getClip();
			BufferedInputStream x = new BufferedInputStream(new FileInputStream(url));
			AudioInputStream inputStream = AudioSystem.getAudioInputStream(x);
			clip.open(inputStream);
			clip.start(); 
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println(e.getMessage());
		}
	}
	
	private void PlayConnectSound() {
		PlaySound(prefs.get("sound_connect", ""));
	}
	
	private void PlayDisconnectSound() {
		PlaySound(prefs.get("sound_disconnect", ""));
	}
	
	public void PlayConversionFinishedSound() {
		PlaySound(prefs.get("sound_conversion_finished", ""));
	}
	
	private void PlayDawingFinishedSound() {
		PlaySound(prefs.get("sound_drawing_finished", ""));
	}
		
	private void SetDrawStyle(int style) {
		prefs.putInt("Draw Style", style);
	}
	private int GetDrawStyle() {
		return prefs.getInt("Draw Style", 0);
	}
	
	
	private void HilbertCurve() {
		Filter_GeneratorHilbertCurve msg = new Filter_GeneratorHilbertCurve();
		msg.Generate( GetTempDestinationFile() );
		previewPane.ZoomToFitPaper();
	}
	
	
	private void TextToGCODE() {
		Filter_GeneratorYourMessageHere msg = new Filter_GeneratorYourMessageHere();

		msg.Generate( GetTempDestinationFile() );

    	previewPane.ZoomToFitPaper();
	}
	

	// appends a message to the log tab and system out.
	public void Log(String msg) {
		// remove the 
		if(msg.indexOf(';') != -1 ) msg = msg.substring(0,msg.indexOf(';'));
		
		msg=msg.replace("\n", "<br>\n")+"\n";
		msg=msg.replace("\n\n","\n");
		logToFile.write(msg);
		logToFile.flush();

		try {
			kit.insertHTML(doc, doc.getLength(), msg, 0, 0, null);
			int over_length = doc.getLength() - msg.length() - 5000;
			doc.remove(0, over_length);
			//logPane.getVerticalScrollBar().setValue(logPane.getVerticalScrollBar().getMaximum());
		} catch (BadLocationException e) {
			// Do we care if it fails?
		} catch (IOException e) {
			// Do we care if it fails?
		}
	}
	
	public void ClearLog() {
		try {
			doc.replace(0, doc.getLength(), "", null);
			kit.insertHTML(doc, 0, "", 0,0,null);
			//logPane.getVerticalScrollBar().setValue(logPane.getVerticalScrollBar().getMaximum());
		} catch (BadLocationException e) {
			// Do we care if it fails?
		} catch (IOException e) {
			// Do we care if it fails?
		}
	}
	
	public void ClosePort() {
		if(portOpened) {
		    if (serialPort != null) {
		        try {
			        serialPort.removeEventListener();
			        serialPort.closePort();
		        } catch (SerialPortException e) {}
		    }

		    ClearLog();
			portOpened=false;
			portConfirmed=false;
			previewPane.setConnected(false);
			UpdateMenuBar();
			PlayDisconnectSound();
			
			// update window title
			mainframe.setTitle(MultilingualSupport.getSingleton().get("TitlePrefix") 
					+ Long.toString(MachineConfiguration.getSingleton().robot_uid) 
					+ MultilingualSupport.getSingleton().get("TitleNotConnected"));
		}
	}
	
	// open a serial connection to a device.  We won't know it's the robot until  
	public int OpenPort(String portName) {
		if(portOpened && portName.equals(recentPort)) return 0;
		
		ClosePort();
		
		Log("<font color='green'>"+MultilingualSupport.getSingleton().get("ConnectingTo") + portName+"...</font>\n");
		
		// open the port
		serialPort = new SerialPort(portName);
		try {
            serialPort.openPort();// Open serial port
            serialPort.setParams(BAUD_RATE,SerialPort.DATABITS_8,SerialPort.STOPBITS_1,SerialPort.PARITY_NONE);
            serialPort.addEventListener(this);
        } catch (SerialPortException e) {
			Log("<span style='color:red'>"+MultilingualSupport.getSingleton().get("PortNotConfigured")+e.getMessage()+"</span>\n");
			return 3;
		}

		Log("<span style='color:green'>"+MultilingualSupport.getSingleton().get("PortOpened")+"</span>\n");
		SetRecentPort(portName);
		portOpened=true;
		lastLineWasCue=false;
		UpdateMenuBar();
		PlayConnectSound();
		try {
			// request HELLO response to update portConfirmed
			serialPort.writeBytes("M100\n".getBytes());
		} catch (SerialPortException e) {}
		
		return 0;
	}

	/**
	 * Check if the robot reports an error and if so what line number 
	 * @return
	 */
	protected int ErrorReported() {
		if(portConfirmed==false) return -1;

		if( serial_recv_buffer.lastIndexOf(nochecksum) != -1 ) {
			String after_error = serial_recv_buffer.substring(serial_recv_buffer.lastIndexOf(nochecksum) + nochecksum.length());
			String x=GetNumberPortion(after_error);
			return Integer.decode(x);
		}
		if( serial_recv_buffer.lastIndexOf(badchecksum) != -1 ) {
			String after_error = serial_recv_buffer.substring(serial_recv_buffer.lastIndexOf(badchecksum) + badchecksum.length());
			String x=GetNumberPortion(after_error);
			return Integer.decode(x);
		}
		if( serial_recv_buffer.lastIndexOf(badlinenum) != -1 ) {
			String after_error = serial_recv_buffer.substring(serial_recv_buffer.lastIndexOf(badlinenum) + badlinenum.length());
			String x=GetNumberPortion(after_error);
			return Integer.decode(x);
		}
		
		return -1;
	}
	
	protected String GetNumberPortion(String src) {
	    int length = src.length();
	    String result = "";
	    for (int i = 0; i < length; i++) {
	        Character character = src.charAt(i);
	        if (Character.isDigit(character)) {
	            result += character;
	        }
	    }
	    return result;
	}
	
	/**
	 * Complete the handshake, load robot-specific configuration, update the menu, repaint the preview with the limits.
	 * @return true if handshake succeeds.
	 */
	public boolean ConfirmPort() {
		if(portConfirmed==true) return true;
		if(serial_recv_buffer.lastIndexOf(hello) < 0) return false;
		
		portConfirmed=true;
		
		String after_hello = serial_recv_buffer.substring(serial_recv_buffer.lastIndexOf(hello) + hello.length());
		MachineConfiguration.getSingleton().ParseRobotUID(after_hello);
		
		mainframe.setTitle(MultilingualSupport.getSingleton().get("TitlePrefix") 
				+ Long.toString(MachineConfiguration.getSingleton().robot_uid) 
				+ MultilingualSupport.getSingleton().get("TitlePostfix"));

		SendConfig();
		previewPane.updateMachineConfig();

		UpdateMenuBar();
		previewPane.setConnected(true);

		return true;
	}
	
	// find all available serial ports for the settings->ports menu.
	public String[] ListSerialPorts() {
        if(System.getProperty("os.name").equals("Mac OS X")){
        	portsDetected = SerialPortList.getPortNames("/dev/");
            //System.out.println("OS X");
        } else {
        	portsDetected = SerialPortList.getPortNames("COM");
            //System.out.println("Windows");
        }
        
	    return portsDetected;
	}
	
	// pull the last connected port from prefs
	public void GetRecentPort() {
		recentPort = prefs.get("recent-port", "");
	}
	
	// update the prefs with the last port connected and refreshes the menus.
	// TODO: only update when the port is confirmed?
	public void SetRecentPort(String portName) {
		prefs.put("recent-port", portName);
		recentPort=portName;
		UpdateMenuBar();
	}
	
	/**
	 * Opens a file.  If the file can be opened, get a drawing time estimate, update recent files list, and repaint the preview tab.
	 * @param filename what file to open
	 */
	public void LoadGCode(String filename) {
		try {
			gcode.Load(filename);
		   	Log("<font color='green'>"+gcode.estimate_count + MultilingualSupport.getSingleton().get("LineSegments")
		   			+ "\n" + gcode.estimated_length + MultilingualSupport.getSingleton().get("Centimeters") + "\n"
		   			+ MultilingualSupport.getSingleton().get("EstimatedTime") + statusBar.formatTime((long)(gcode.estimated_time)) + "s.</font>\n");
	    }
	    catch(IOException e) {
	    	Log("<span style='color:red'>"+MultilingualSupport.getSingleton().get("FileNotOpened") + e.getLocalizedMessage()+"</span>\n");
	    	RemoveRecentFile(filename);
	    	return;
	    }
	    
	    previewPane.setGCode(gcode.lines);
	    Halt();
	}
	
	public String GetTempDestinationFile() {
		return System.getProperty("user.dir")+"/temp.ngc";
	}


	protected boolean ChooseDXFConversionOptions() {
		final JDialog driver = new JDialog(mainframe,MultilingualSupport.getSingleton().get("ConversionOptions"),true);
		driver.setLayout(new GridBagLayout());
		
		final JSlider input_paper_margin = new JSlider(JSlider.HORIZONTAL, 0, 50, 100-(int)(MachineConfiguration.getSingleton().paper_margin*100));
		input_paper_margin.setMajorTickSpacing(10);
		input_paper_margin.setMinorTickSpacing(5);
		input_paper_margin.setPaintTicks(false);
		input_paper_margin.setPaintLabels(true);
		
		//final JCheckBox allow_metrics = new JCheckBox(String.valueOf("I want to add the distance drawn to the // total"));
		//allow_metrics.setSelected(allowMetrics);
		
		final JCheckBox reverse_h = new JCheckBox(MultilingualSupport.getSingleton().get("FlipForGlass"));
		reverse_h.setSelected(MachineConfiguration.getSingleton().reverseForGlass);

		final JButton cancel = new JButton(MultilingualSupport.getSingleton().get("Cancel"));
		final JButton save = new JButton(MultilingualSupport.getSingleton().get("Start"));
		
		GridBagConstraints c = new GridBagConstraints();
		//c.gridwidth=4; 	c.gridx=0;  c.gridy=0;  driver.add(allow_metrics,c);

		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=0;  c.gridy=8;  driver.add(new JLabel(MultilingualSupport.getSingleton().get("PaperMargin")),c);
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=3;	c.gridx=1;  c.gridy=8;  driver.add(input_paper_margin,c);
		
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;  c.gridx=1;  c.gridy=11; driver.add(reverse_h,c);
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=2;  c.gridy=12;  driver.add(save,c);
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;	c.gridx=3;  c.gridy=12;  driver.add(cancel,c);

		startConvertingNow = false;
		
		ActionListener driveButtons = new ActionListener() {
			  public void actionPerformed(ActionEvent e) {
					Object subject = e.getSource();
					if(subject == save) {
						MachineConfiguration.getSingleton().paper_margin=(100-input_paper_margin.getValue())*0.01;
						MachineConfiguration.getSingleton().reverseForGlass=reverse_h.isSelected();
						MachineConfiguration.getSingleton().SaveConfig();
						startConvertingNow=true;
						driver.dispose();
					}
					if(subject == cancel) {
						driver.dispose();
					}
			  }
		};
			
		save.addActionListener(driveButtons);
		cancel.addActionListener(driveButtons);
	    driver.getRootPane().setDefaultButton(save);
		driver.pack();
		driver.setVisible(true);
		
		return startConvertingNow;
	}
	
	protected boolean LoadDXF(String filename) {
		if( ChooseDXFConversionOptions() == false ) return false;

        // where to save temp output file?
		final String destinationFile = GetTempDestinationFile();
		final String srcFile = filename;
		
		final ProgressMonitor pm = new ProgressMonitor(null, MultilingualSupport.getSingleton().get("Converting"), "", 0, 100);
		pm.setProgress(0);
		pm.setMillisToPopup(0);
		
		final SwingWorker<Void,Void> s = new SwingWorker<Void,Void>() {
			public boolean ok=false;
			
			@Override
			public Void doInBackground() {
				Log("<font color='green'>"+MultilingualSupport.getSingleton().get("Converting")+" "+destinationFile+"</font>\n");

				Parser parser = ParserBuilder.createDefaultParser();

				double dxf_x2=0;
				double dxf_y2=0;
				OutputStreamWriter out=null;

				try {
					out = new OutputStreamWriter(new FileOutputStream(destinationFile),"UTF-8");
					MachineConfiguration mc = MachineConfiguration.getSingleton();
					DrawingTool tool = mc.GetCurrentTool();
					out.write(mc.GetConfigLine()+";\n");
					out.write(mc.GetBobbinLine()+";\n");
					out.write("G00 G90;\n");
					tool.WriteChangeTo(out);
					tool.WriteOff(out);
					
					parser.parse(srcFile, DXFParser.DEFAULT_ENCODING);
					DXFDocument doc = parser.getDocument();
					Bounds b = doc.getBounds();
					double width = b.getMaximumX() - b.getMinimumX();
					double height = b.getMaximumY() - b.getMinimumY();
					double cx = ( b.getMaximumX() + b.getMinimumX() ) / 2.0f;
					double cy = ( b.getMaximumY() + b.getMinimumY() ) / 2.0f;
					double sy = mc.GetPaperHeight()*10/height;
					double sx = mc.GetPaperWidth()*10/width;
					double scale = (sx<sy? sx:sy ) * mc.paper_margin;
					sx = scale * (MachineConfiguration.getSingleton().reverseForGlass? -1 : 1);
					sy = scale;
					
					// count all entities in all layers
					Iterator<DXFLayer> layer_iter = (Iterator<DXFLayer>)doc.getDXFLayerIterator();
					int entity_total=0;
					int entity_count=0;
					while(layer_iter.hasNext()) {
						DXFLayer layer = (DXFLayer)layer_iter.next();
						Log("<font color='yellow'>Found layer "+layer.getName()+"</font>\n");
						Iterator<String> entity_iter = (Iterator<String>)layer.getDXFEntityTypeIterator();
						while(entity_iter.hasNext()) {
							String entity_type = (String)entity_iter.next();
							List<DXFEntity> entity_list = (List<DXFEntity>)layer.getDXFEntities(entity_type);
							Log("<font color='yellow'>+ Found "+entity_list.size()+" of type "+entity_type+"</font>\n");
							entity_total+=entity_list.size();
						}
					}
					// set the progress meter
					pm.setMinimum(0);
					pm.setMaximum(entity_total);
							
					// convert each entity
					layer_iter = doc.getDXFLayerIterator();
					while(layer_iter.hasNext()) {
						DXFLayer layer = (DXFLayer)layer_iter.next();

						Iterator<String> entity_type_iter = (Iterator<String>)layer.getDXFEntityTypeIterator();
						while(entity_type_iter.hasNext()) {
							String entity_type = (String)entity_type_iter.next();
							List<DXFEntity> entity_list = layer.getDXFEntities(entity_type);
							
							if(entity_type.equals(DXFConstants.ENTITY_TYPE_LINE)) {
								for(int i=0;i<entity_list.size();++i) {
									pm.setProgress(entity_count++);
									DXFLine entity = (DXFLine)entity_list.get(i);
									Point start = entity.getStartPoint();
									Point end = entity.getEndPoint();

									double x=(start.getX()-cx)*sx;
									double y=(start.getY()-cy)*sy;
									double x2=(end.getX()-cx)*sx;
									double y2=(end.getY()-cy)*sy;
									
									// is it worth drawing this line?
									double dx = x2-x;
									double dy = y2-y;
									if(dx*dx+dy*dy < tool.GetDiameter()/2.0) {
										continue;
									}
									
									dx = dxf_x2 - x;
									dy = dxf_y2 - y;

									if(dx*dx+dy*dy > tool.GetDiameter()/2.0) {
										if(tool.DrawIsOn()) {
											tool.WriteOff(out);
										}
										tool.WriteMoveTo(out, (float)x,(float)y);
									}
									if(tool.DrawIsOff()) {
										tool.WriteOn(out);
									}
									tool.WriteMoveTo(out, (float)x2,(float)y2);
									dxf_x2=x2;
									dxf_y2=y2;
								}
							} else if(entity_type.equals(DXFConstants.ENTITY_TYPE_SPLINE)) {
								for(int i=0;i<entity_list.size();++i) {
									pm.setProgress(entity_count++);
									DXFSpline entity = (DXFSpline)entity_list.get(i);
									DXFPolyline polyLine = DXFSplineConverter.toDXFPolyline(entity,30);
									boolean first=true;
									for(int j=0;j<polyLine.getVertexCount();++j) {
										DXFVertex v = polyLine.getVertex(j);
										double x = (v.getX()-cx)*sx;
										double y = (v.getY()-cy)*sy;
										double dx = dxf_x2 - x;
										double dy = dxf_y2 - y;
										
										if(first==true) {
											first=false;
											if(dx*dx+dy*dy > tool.GetDiameter()/2.0) {
												// line does not start at last tool location, lift and move.
												if(tool.DrawIsOn()) {
													tool.WriteOff(out);
												}
												tool.WriteMoveTo(out, (float)x,(float)y);
											}
											// else line starts right here, do nothing.
										} else {
											// not the first point, draw.
											if(tool.DrawIsOff()) tool.WriteOn(out);
											if(j<polyLine.getVertexCount()-1 && dx*dx+dy*dy<tool.GetDiameter()/2.0) continue;  // less than 1mm movement?  Skip it. 
											tool.WriteMoveTo(out, (float)x,(float)y);
										}
										dxf_x2=x;
										dxf_y2=y;
									}
								}
							} else if(entity_type.equals(DXFConstants.ENTITY_TYPE_POLYLINE)) {
								for(int i=0;i<entity_list.size();++i) {
									pm.setProgress(entity_count++);
									DXFPolyline entity = (DXFPolyline)entity_list.get(i);
									boolean first=true;
									for(int j=0;j<entity.getVertexCount();++j) {
										DXFVertex v = entity.getVertex(j);
										double x = (v.getX()-cx)*sx;
										double y = (v.getY()-cy)*sy;
										double dx = dxf_x2 - x;
										double dy = dxf_y2 - y;
										
										if(first==true) {
											first=false;
											if(dx*dx+dy*dy > tool.GetDiameter()/2.0) {
												// line does not start at last tool location, lift and move.
												if(tool.DrawIsOn()) {
													tool.WriteOff(out);
												}
												tool.WriteMoveTo(out, (float)x,(float)y);
											}
											// else line starts right here, do nothing.
										} else {
											// not the first point, draw.
											if(tool.DrawIsOff()) tool.WriteOn(out);
											if(j<entity.getVertexCount()-1 && dx*dx+dy*dy<tool.GetDiameter()/2.0) continue;  // less than 1mm movement?  Skip it. 
											tool.WriteMoveTo(out, (float)x,(float)y);
										}
										dxf_x2=x;
										dxf_y2=y;
									}
								}
							}
						}
					}

					// entities finished.  Close up file.
					tool.WriteOff(out);
					tool.WriteMoveTo(out, 0, 0);
					
					ok=true;
				} catch(IOException e) {
					e.printStackTrace();
				} catch (org.kabeja.parser.ParseException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} finally {
					try {
						if(out!=null) out.close();
					} catch(IOException e) {
						e.printStackTrace();
					}
						
				}
				
				pm.setProgress(100);
			    return null;
			}
			
			@Override
			public void done() {
				pm.close();
				Log("<font color='green'>"+MultilingualSupport.getSingleton().get("Finished")+"</font>\n");
				PlayConversionFinishedSound();
				if(ok) LoadGCode(destinationFile);
			    Halt();
			}
		};
		
		s.addPropertyChangeListener(new PropertyChangeListener() {
		    // Invoked when task's progress property changes.
		    public void propertyChange(PropertyChangeEvent evt) {
		        if ("progress" == evt.getPropertyName() ) {
		            int progress = (Integer) evt.getNewValue();
		            pm.setProgress(progress);
		            String message = String.format("%d%%\n", progress);
		            pm.setNote(message);
		            if(s.isDone()) {
	                	Makelangelo.getSingleton().Log("<font color='green'>"+MultilingualSupport.getSingleton().get("Finished")+"</font>\n");
		            } else if (s.isCancelled() || pm.isCanceled()) {
		                if (pm.isCanceled()) {
		                    s.cancel(true);
		                }
	                    Makelangelo.getSingleton().Log("<font color='green'>"+MultilingualSupport.getSingleton().get("Cancelled")+"</font>\n");
		            }
		        }
		    }
		});
		
		s.execute();
		
		return true;
	}

	protected boolean ChooseImageConversionOptions() {
		final JDialog driver = new JDialog(mainframe,MultilingualSupport.getSingleton().get("ConversionOptions"),true);
		driver.setLayout(new GridBagLayout());
		
		final JSlider input_paper_margin = new JSlider(JSlider.HORIZONTAL, 0, 50, 100-(int)(MachineConfiguration.getSingleton().paper_margin*100));
		input_paper_margin.setMajorTickSpacing(10);
		input_paper_margin.setMinorTickSpacing(5);
		input_paper_margin.setPaintTicks(false);
		input_paper_margin.setPaintLabels(true);
		
		//final JCheckBox allow_metrics = new JCheckBox(String.valueOf("I want to add the distance drawn to the // total"));
		//allow_metrics.setSelected(allowMetrics);
		
		final JCheckBox reverse_h = new JCheckBox(MultilingualSupport.getSingleton().get("FlipForGlass"));
		reverse_h.setSelected(MachineConfiguration.getSingleton().reverseForGlass);

		final JComboBox<String> input_draw_style = new JComboBox<String>(filter_names);
		input_draw_style.setSelectedIndex(GetDrawStyle());
		
		final JButton cancel = new JButton("Cancel");
		final JButton save = new JButton("Start");
		
		GridBagConstraints c = new GridBagConstraints();
		//c.gridwidth=4; 	c.gridx=0;  c.gridy=0;  driver.add(allow_metrics,c);

		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=0;  c.gridy=8;  driver.add(new JLabel(MultilingualSupport.getSingleton().get("PaperMargin")),c);
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=3;	c.gridx=1;  c.gridy=8;  driver.add(input_paper_margin,c);
		
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=0;  c.gridy=9;  driver.add(new JLabel(MultilingualSupport.getSingleton().get("ConversionStyle")),c);
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=3;	c.gridx=1;	c.gridy=9;	driver.add(input_draw_style,c);
		
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;  c.gridx=1;  c.gridy=11; driver.add(reverse_h,c);
		
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=2;  c.gridy=12;  driver.add(save,c);
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;	c.gridx=3;  c.gridy=12;  driver.add(cancel,c);

		startConvertingNow = false;
		
		ActionListener driveButtons = new ActionListener() {
			  public void actionPerformed(ActionEvent e) {
					Object subject = e.getSource();
					if(subject == save) {
						MachineConfiguration.getSingleton().paper_margin=(100-input_paper_margin.getValue())*0.01;
						MachineConfiguration.getSingleton().reverseForGlass=reverse_h.isSelected();
						SetDrawStyle(input_draw_style.getSelectedIndex());
						MachineConfiguration.getSingleton().SaveConfig();
						startConvertingNow=true;
						driver.dispose();
					}
					if(subject == cancel) {
						driver.dispose();
					}
			  }
		};
			
		save.addActionListener(driveButtons);
		cancel.addActionListener(driveButtons);
	    driver.getRootPane().setDefaultButton(save);
		driver.pack();
		driver.setVisible(true);
		
		return startConvertingNow;
	}
	
	
	public boolean LoadImage(String filename) {
        // where to save temp output file?
		final String sourceFile = filename;
		final String destinationFile = GetTempDestinationFile();
		
		if( ChooseImageConversionOptions() == false ) return false;

		final ProgressMonitor pm = new ProgressMonitor(null, MultilingualSupport.getSingleton().get("Converting"), "", 0, 100);
		pm.setProgress(0);
		pm.setMillisToPopup(0);
		
		final SwingWorker<Void,Void> s = new SwingWorker<Void,Void>() {
			@Override
			public Void doInBackground() {
				// read in image
				BufferedImage img;
				try {
					Log("<font color='green'>"+MultilingualSupport.getSingleton().get("Converting")+" "+destinationFile+"</font>\n");
					// convert with style
					img = ImageIO.read(new File(sourceFile));
					
					int style = GetDrawStyle();
					image_converters[style].SetParent(this);
					image_converters[style].SetProgressMonitor(pm);
					image_converters[style].SetDestinationFile(destinationFile);
					image_converters[style].Convert(img);
				}
				catch(IOException e) {
					Log("<font color='red'>"+MultilingualSupport.getSingleton().get("Failed")+e.getLocalizedMessage()+"</font>\n");
					RemoveRecentFile(sourceFile);
				}

				pm.setProgress(100);
			    return null;
			}
			
			@Override
			public void done() {
				pm.close();
				Log("<font color='green'>"+MultilingualSupport.getSingleton().get("Finished")+"</font>\n");
				PlayConversionFinishedSound();
				LoadGCode(destinationFile);
			}
		};
		
		s.addPropertyChangeListener(new PropertyChangeListener() {
		    // Invoked when task's progress property changes.
		    public void propertyChange(PropertyChangeEvent evt) {
		        if ("progress" == evt.getPropertyName() ) {
		            int progress = (Integer) evt.getNewValue();
		            pm.setProgress(progress);
		            String message = String.format("%d%%.\n", progress);
		            pm.setNote(message);
		            if(s.isDone()) {
	                	Makelangelo.getSingleton().Log("<font color='green'>"+MultilingualSupport.getSingleton().get("Finished")+"</font>\n");
		            } else if (s.isCancelled() || pm.isCanceled()) {
		                if (pm.isCanceled()) {
		                    s.cancel(true);
		                }
	                    Makelangelo.getSingleton().Log("<font color='green'>"+MultilingualSupport.getSingleton().get("Cancelled")+"</font>\n");
		            }
		        }
		    }
		});
		
		s.execute();
		
		return true;
	}
	
	
	public boolean IsFileLoaded() {
		return ( gcode.fileOpened && gcode.lines != null && gcode.lines.size() > 0 );
	}
	
	/**
	 * changes the order of the recent files list in the File submenu, saves the updated prefs, and refreshes the menus.
	 * @param filename the file to push to the top of the list.
	 */
	public void UpdateRecentFiles(String filename) {
		int cnt = recentFiles.length;
		String [] newFiles = new String[cnt];
		
		newFiles[0]=filename;
		
		int i,j=1;
		for(i=0;i<cnt;++i) {
			if(!filename.equals(recentFiles[i]) && recentFiles[i] != "") {
				newFiles[j++] = recentFiles[i];
				if(j == cnt ) break;
			}
		}

		recentFiles=newFiles;

		// update prefs
		for(i=0;i<cnt;++i) {
			if( recentFiles[i]!=null && !recentFiles[i].isEmpty()) {
				prefs.put("recent-files-"+i, recentFiles[i]);
			}
		}
		
		UpdateMenuBar();
	}
	
	// A file failed to load.  Remove it from recent files, refresh the menu bar.
	public void RemoveRecentFile(String filename) {
		int i;
		for(i=0;i<recentFiles.length-1;++i) {
			if(recentFiles[i]==filename) {
				break;
			}
		}
		for(;i<recentFiles.length-1;++i) {
			recentFiles[i]=recentFiles[i+1];
		}
		recentFiles[recentFiles.length-1]="";

		// update prefs
		for(i=0;i<recentFiles.length;++i) {
			if(recentFiles[i]!=null && !recentFiles[i].isEmpty()) {
				prefs.put("recent-files-"+i, recentFiles[i]);
			}
		}
		prefs.remove("recent-files-"+(i-1));
		
		UpdateMenuBar();
	}
	
	// Load recent files from prefs
	public void GetRecentFiles() {
		recentFiles = new String[10];
		
		int i;
		for(i=0;i<recentFiles.length;++i) {
			recentFiles[i] = prefs.get("recent-files-"+i, "");
		}
	}	
	
	public boolean IsFileGcode(String filename) {
		String ext=filename.substring(filename.lastIndexOf('.'));
    	return (ext.equalsIgnoreCase(".ngc") || ext.equalsIgnoreCase(".gc"));
	}
	
	public boolean IsFileDXF(String filename) {
		String ext=filename.substring(filename.lastIndexOf('.'));
    	return (ext.equalsIgnoreCase(".dxf"));
	}
	
	public boolean IsFileImage(String filename) {
		String ext=filename.substring(filename.lastIndexOf('.'));
    	return ext.equalsIgnoreCase(".jpg")
    			|| ext.equalsIgnoreCase(".png")
    			|| ext.equalsIgnoreCase(".bmp")
    			|| ext.equalsIgnoreCase(".gif");
	}
	
	// User has asked that a file be opened.
	public void OpenFileOnDemand(String filename) {
		Log("<font color='green'>" + MultilingualSupport.getSingleton().get("OpeningFile") + filename + "...</font>\n");

	   	UpdateRecentFiles(filename);
	   	
	   	if(IsFileGcode(filename)) {
			LoadGCode(filename);
    	} else if(IsFileDXF(filename)) {
    		LoadDXF(filename);
    	} else if(IsFileImage(filename)) {
    		LoadImage(filename);
    	} else {
    		Log("<font color='red'>"+MultilingualSupport.getSingleton().get("UnknownFileType")+"</font>\n");
    	}

    	previewPane.ZoomToFitPaper();
    	statusBar.Clear();
	}

	// creates a file open dialog. If you don't cancel it opens that file.
	public void OpenFileDialog() {
	    // Note: source for ExampleFileFilter can be found in FileChooserDemo,
	    // under the demo/jfc directory in the Java 2 SDK, Standard Edition.
		String filename = (recentFiles[0].length()>0) ? filename=recentFiles[0] : "";

		FileFilter filterGCODE = new FileNameExtensionFilter(MultilingualSupport.getSingleton().get("FileTypeGCode"), "ngc");
		FileFilter filterImage = new FileNameExtensionFilter(MultilingualSupport.getSingleton().get("FileTypeImage"), "jpg", "jpeg", "png", "wbmp", "bmp", "gif");
		FileFilter filterDXF   = new FileNameExtensionFilter(MultilingualSupport.getSingleton().get("FileTypeDXF"), "dxf");
		 
		JFileChooser fc = new JFileChooser(new File(filename));
		fc.addChoosableFileFilter(filterImage);
		fc.addChoosableFileFilter(filterDXF);
		fc.addChoosableFileFilter(filterGCODE);
	    if(fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
	    	String selectedFile=fc.getSelectedFile().getAbsolutePath();

	    	// if machine is not yet calibrated
	    	if(MachineConfiguration.getSingleton().IsPaperConfigured() == false) {
	    		JOptionPane.showMessageDialog(null,MultilingualSupport.getSingleton().get("SetPaperSize"));
	    		return;
	    	}
	    	OpenFileOnDemand(selectedFile);
	    }
	}
	
	private void SaveFileDialog() {
	    // Note: source for ExampleFileFilter can be found in FileChooserDemo,
	    // under the demo/jfc directory in the Java 2 SDK, Standard Edition.
		String filename = (recentFiles[0].length()>0) ? filename=recentFiles[0] : "";

		FileFilter filterGCODE = new FileNameExtensionFilter(MultilingualSupport.getSingleton().get("FileTypeGCode"), "ngc");
		
		JFileChooser fc = new JFileChooser(new File(filename));
		fc.addChoosableFileFilter(filterGCODE);
	    if(fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
	    	String selectedFile=fc.getSelectedFile().getAbsolutePath();

			if(!selectedFile.toLowerCase().endsWith(".ngc")) {
				selectedFile+=".ngc";
			}

	    	try {
	    		gcode.Save(selectedFile);
	    	}
		    catch(IOException e) {
		    	Log("<span style='color:red'>"+MultilingualSupport.getSingleton().get("Failed")+e.getMessage()+"</span>\n");
		    	return;
		    }
	    }
	}
	
	public void GoHome() {
		SendLineToRobot("G00 F"+feed_rate+" X0 Y0");
	}
	
	private String SelectFile() {
		JFileChooser choose = new JFileChooser();
	    int returnVal = choose.showOpenDialog(this);
	    if (returnVal == JFileChooser.APPROVE_OPTION) {
	        File file = choose.getSelectedFile();
	        return file.getAbsolutePath();
	    } else {
	        //System.out.println("File access cancelled by user.");
		    return "";
	    }
	}
	
	// Adjust sound preferences
	protected void AdjustSounds() {
		final JDialog driver = new JDialog(mainframe,MultilingualSupport.getSingleton().get("MenuSoundsTitle"),true);
		driver.setLayout(new GridBagLayout());
		
		final JTextField sound_connect = new JTextField(prefs.get("sound_connect",""),32);
		final JTextField sound_disconnect = new JTextField(prefs.get("sound_disconnect", ""),32);
		final JTextField sound_conversion_finished = new JTextField(prefs.get("sound_conversion_finished", ""),32);
		final JTextField sound_drawing_finished = new JTextField(prefs.get("sound_drawing_finished", ""),32);

		final JButton change_sound_connect = new JButton(MultilingualSupport.getSingleton().get("MenuSoundsConnect"));
		final JButton change_sound_disconnect = new JButton(MultilingualSupport.getSingleton().get("MenuSoundsDisconnect"));
		final JButton change_sound_conversion_finished = new JButton(MultilingualSupport.getSingleton().get("MenuSoundsFinishConvert"));
		final JButton change_sound_drawing_finished = new JButton(MultilingualSupport.getSingleton().get("MenuSoundsFinishDraw"));
		
		//final JCheckBox allow_metrics = new JCheckBox(String.valueOf("I want to add the distance drawn to the // total"));
		//allow_metrics.setSelected(allowMetrics);
		
		final JButton cancel = new JButton(MultilingualSupport.getSingleton().get("Cancel"));
		final JButton save = new JButton(MultilingualSupport.getSingleton().get("Save"));
		
		GridBagConstraints c = new GridBagConstraints();
		//c.gridwidth=4; 	c.gridx=0;  c.gridy=0;  driver.add(allow_metrics,c);

		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=0;  c.gridy=3;  driver.add(change_sound_connect,c);								c.anchor=GridBagConstraints.WEST;	c.gridwidth=3;	c.gridx=1;  c.gridy=3;  driver.add(sound_connect,c);
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=0;  c.gridy=4;  driver.add(change_sound_disconnect,c);							c.anchor=GridBagConstraints.WEST;	c.gridwidth=3;	c.gridx=1;  c.gridy=4;  driver.add(sound_disconnect,c);
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=0;  c.gridy=5;  driver.add(change_sound_conversion_finished,c);					c.anchor=GridBagConstraints.WEST;	c.gridwidth=3;	c.gridx=1;  c.gridy=5;  driver.add(sound_conversion_finished,c);
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=0;  c.gridy=6;  driver.add(change_sound_drawing_finished,c);					c.anchor=GridBagConstraints.WEST;	c.gridwidth=3;	c.gridx=1;  c.gridy=6;  driver.add(sound_drawing_finished,c);
		
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=2;  c.gridy=12;  driver.add(save,c);
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;	c.gridx=3;  c.gridy=12;  driver.add(cancel,c);
		
		ActionListener driveButtons = new ActionListener() {
			  public void actionPerformed(ActionEvent e) {
					Object subject = e.getSource();
					if(subject == change_sound_connect) sound_connect.setText(SelectFile());
					if(subject == change_sound_disconnect) sound_disconnect.setText(SelectFile());
					if(subject == change_sound_conversion_finished) sound_conversion_finished.setText(SelectFile());
					if(subject == change_sound_drawing_finished) sound_drawing_finished.setText(SelectFile());

					if(subject == save) {
						//allowMetrics = allow_metrics.isSelected();
						prefs.put("sound_connect",sound_connect.getText());
						prefs.put("sound_disconnect",sound_disconnect.getText());
						prefs.put("sound_conversion_finished",sound_conversion_finished.getText());
						prefs.put("sound_drawing_finished",sound_drawing_finished.getText());
						MachineConfiguration.getSingleton().SaveConfig();
						driver.dispose();
					}
					if(subject == cancel) {
						driver.dispose();
					}
			  }
		};

		change_sound_connect.addActionListener(driveButtons);
		change_sound_disconnect.addActionListener(driveButtons);
		change_sound_conversion_finished.addActionListener(driveButtons);
		change_sound_drawing_finished.addActionListener(driveButtons);
			
		save.addActionListener(driveButtons);
		cancel.addActionListener(driveButtons);
	    driver.getRootPane().setDefaultButton(save);
		driver.pack();
		driver.setVisible(true);
	}

    // Adjust graphics preferences	
	protected void AdjustGraphics() {
		final Preferences graphics_prefs = Preferences.userRoot().node("DrawBot").node("Graphics");
		
		final JDialog driver = new JDialog(mainframe,MultilingualSupport.getSingleton().get("MenuGraphicsTitle"),true);
		driver.setLayout(new GridBagLayout());
		
		//final JCheckBox allow_metrics = new JCheckBox(String.valueOf("I want to add the distance drawn to the // total"));
		//allow_metrics.setSelected(allowMetrics);
		
		final JCheckBox show_pen_up = new JCheckBox(MultilingualSupport.getSingleton().get("MenuGraphicsPenUp"));
		final JCheckBox antialias_on = new JCheckBox(MultilingualSupport.getSingleton().get("MenuGraphicsAntialias"));
		final JCheckBox speed_over_quality = new JCheckBox(MultilingualSupport.getSingleton().get("MenuGraphicsSpeedVSQuality"));
		final JCheckBox draw_all_while_running = new JCheckBox(MultilingualSupport.getSingleton().get("MenuGraphicsDrawWhileRunning"));

		show_pen_up.setSelected(graphics_prefs.getBoolean("show pen up", false));
		antialias_on.setSelected(graphics_prefs.getBoolean("antialias", true));
		speed_over_quality.setSelected(graphics_prefs.getBoolean("speed over quality", true));
		draw_all_while_running.setSelected(graphics_prefs.getBoolean("Draw all while running", true));
		
		final JButton cancel = new JButton(MultilingualSupport.getSingleton().get("Cancel"));
		final JButton save = new JButton(MultilingualSupport.getSingleton().get("Save"));
		
		GridBagConstraints c = new GridBagConstraints();
		//c.gridwidth=4; 	c.gridx=0;  c.gridy=0;  driver.add(allow_metrics,c);

		int y=0;
		
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;	c.gridx=1;  c.gridy=y;  driver.add(show_pen_up,c);  y++;
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;	c.gridx=1;  c.gridy=y;  driver.add(draw_all_while_running,c);  y++;
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;	c.gridx=1;  c.gridy=y;  driver.add(antialias_on,c);  y++;
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;	c.gridx=1;  c.gridy=y;  driver.add(speed_over_quality,c);  y++;
		
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=2;  c.gridy=y;  driver.add(save,c);
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;	c.gridx=3;  c.gridy=y;  driver.add(cancel,c);
		
		ActionListener driveButtons = new ActionListener() {
			  public void actionPerformed(ActionEvent e) {
					Object subject = e.getSource();
					if(subject == save) {
						//allowMetrics = allow_metrics.isSelected();
						graphics_prefs.putBoolean("show pen up", show_pen_up.isSelected());
						graphics_prefs.putBoolean("antialias", antialias_on.isSelected());
						graphics_prefs.putBoolean("speed over quality", speed_over_quality.isSelected());
						graphics_prefs.putBoolean("Draw all while running", draw_all_while_running.isSelected());

						previewPane.setShowPenUp(show_pen_up.isSelected());
						driver.dispose();
					}
					if(subject == cancel) {
						driver.dispose();
					}
			  }
		};

		save.addActionListener(driveButtons);
		cancel.addActionListener(driveButtons);
	    driver.getRootPane().setDefaultButton(save);
		driver.pack();
		driver.setVisible(true);
	}
	
	// Send the machine configuration to the robot
	protected void SendConfig() {
		if(!portConfirmed) return;
		
		// Send a command to the robot with new configuration values
		SendLineToRobot(MachineConfiguration.getSingleton().GetConfigLine());
		SendLineToRobot(MachineConfiguration.getSingleton().GetBobbinLine());
		SendLineToRobot("G92 X0 Y0");
	}
	
	
	// Take the next line from the file and send it to the robot, if permitted. 
	public void SendFileCommand() {
		if(running==false || paused==true || gcode.fileOpened==false || portConfirmed==false || gcode.linesProcessed>=gcode.linesTotal) return;
		
		String line;
		do {			
			// are there any more commands?
			// TODO: find out how far the pen moved each line and add it to the distance total.
			int line_number = gcode.linesProcessed;
			gcode.linesProcessed++;
			line=gcode.lines.get(line_number).trim();
			//if(line.length()>3) { // XXX: comment out to avoid BADLINENUM 
				line="N"+line_number+" "+line;
			//}
			line += GenerateChecksum(line);
			
			previewPane.setLinesProcessed(gcode.linesProcessed);
			statusBar.SetProgress(gcode.linesProcessed, gcode.linesTotal);
			// loop until we find a line that gets sent to the robot, at which point we'll
			// pause for the robot to respond.  Also stop at end of file.
		} while(ProcessLine(line) && gcode.linesProcessed<gcode.linesTotal);
		
		if(gcode.linesProcessed==gcode.linesTotal) {
			// end of file
			PlayDawingFinishedSound();
			Halt();
			SayHooray();
		}
	}
	
	
	private void SayHooray() {
		long num_lines = gcode.linesProcessed;
		
		JOptionPane.showMessageDialog(null,
				MultilingualSupport.getSingleton().get("Finished") +
				num_lines +
				MultilingualSupport.getSingleton().get("LineSegments") + 
				"\n" +
				statusBar.GetElapsed() +
				"\n" +
				MultilingualSupport.getSingleton().get("SharePromo")
				);
	}
	
	
	private void ChangeToTool(String changeToolString) {
		int i=Integer.decode(changeToolString);
		
		MachineConfiguration mc = MachineConfiguration.getSingleton();
		String [] toolNames = mc.getToolNames();
		
		if(i<0 || i>toolNames.length) {
			Log("<span style='color:red'>"+MultilingualSupport.getSingleton().get("InvalidTool")+i+"</span>");
			i=0;
		}
		JOptionPane.showMessageDialog(null,MultilingualSupport.getSingleton().get("ChangeToolPrefix") + toolNames[i] + MultilingualSupport.getSingleton().get("ChangeToolPostfix"));
	}
	
	
	/**
	 * removes comments, processes commands drawbot shouldn't have to handle.
	 * @param line command to send
	 * @return true if the robot is ready for another command to be sent.
	 */
	public boolean ProcessLine(String line) {
		// tool change request?
		String [] tokens = line.split("(\\s|;)");

		// tool change?
		if(Arrays.asList(tokens).contains("M06") || Arrays.asList(tokens).contains("M6")) {
			for(int i=0;i<tokens.length;++i) {
				if(tokens[i].startsWith("T")) {
					ChangeToTool(tokens[i].substring(1));
				}
			}
		}
		
		// end of program?
		if(tokens[0]=="M02" || tokens[0]=="M2" || tokens[0]=="M30") {
			PlayDawingFinishedSound();
			Halt();
			return false;
		}
		
		
		// send relevant part of line to the robot
		SendLineToRobot(line);
		
		return false;
	}
	
	
	protected String GenerateChecksum(String line) {
		byte checksum=0;
		
		for( int i=0; i<line.length(); ++i ) {
			checksum ^= line.charAt(i);
		}
		
		return "*"+((int)checksum);
	}
	

	/**
	 * Sends a single command the robot.  Could be anything.
	 * @param line command to send.
	 * @return true means the command is sent.  false means it was not.
	 */
	public void SendLineToRobot(String line) {
		if(!portConfirmed) return;
		if(line.trim().equals("")) return;
		String reportedline = line;
		if(line.contains(";")) {
			String [] lines = line.split(";");
			reportedline = lines[0];
		}
		Log("<font color='white'>"+reportedline+"</font>");
		line += "\n";
		
		try {
			serialPort.writeBytes(line.getBytes());
		}
		catch(SerialPortException e) {}
	}
	
	/**
	 * stop sending file commands to the robot.
	 * TODO add an e-stop command?
	 */
	public void Halt() {
		running=false;
		paused=false;
	    previewPane.setLinesProcessed(0);
		previewPane.setRunning(running);
		UpdateMenuBar();
	}

	/**
	 * open a dialog to ask for the line number.
	 * @return true if "ok" is pressed, false if the window is closed any other way.
	 */
	private boolean getStartingLineNumber() {
		dialog_result=false;
		
		final JDialog driver = new JDialog(mainframe,MultilingualSupport.getSingleton().get("StartAt"),true);
		driver.setLayout(new GridBagLayout());		
		final JTextField starting_line = new JTextField("0",8);
		final JButton cancel = new JButton(MultilingualSupport.getSingleton().get("Cancel"));
		final JButton start = new JButton(MultilingualSupport.getSingleton().get("Start"));
		GridBagConstraints c = new GridBagConstraints();
		c.gridwidth=2;	c.gridx=0;  c.gridy=0;  driver.add(new JLabel(MultilingualSupport.getSingleton().get("StartAtLine")),c);
		c.gridwidth=2;	c.gridx=2;  c.gridy=0;  driver.add(starting_line,c);
		c.gridwidth=1;	c.gridx=0;  c.gridy=1;  driver.add(cancel,c);
		c.gridwidth=1;	c.gridx=2;  c.gridy=1;  driver.add(start,c);
		
		ActionListener driveButtons = new ActionListener() {
			  public void actionPerformed(ActionEvent e) {
					Object subject = e.getSource();
					
					if(subject == start) {
						gcode.linesProcessed=Integer.decode(starting_line.getText());
						SendLineToRobot("M110 N"+gcode.linesProcessed);
						dialog_result=true;
						driver.dispose();
					}
					if(subject == cancel) {
						dialog_result=false;
						driver.dispose();
					}
			  }
		};

		start.addActionListener(driveButtons);
		cancel.addActionListener(driveButtons);
	    driver.getRootPane().setDefaultButton(start);
		driver.pack();
		driver.setVisible(true);  // modal
		
		return dialog_result;
	}

	private void StartDrawing() {
		paused=false;
		running=true;
		previewPane.setRunning(running);
		UpdateMenuBar();
		statusBar.Start();
		SendFileCommand();
	}
	
	// The user has done something.  respond to it.
	public void actionPerformed(ActionEvent e) {
		Object subject = e.getSource();
		
		if( subject == buttonZoomIn ) {
			previewPane.ZoomIn();
			return;
		}
		if( subject == buttonZoomOut ) {
			previewPane.ZoomOut();
			return;
		}
		if( subject == buttonZoomToFit ) {
			previewPane.ZoomToFitPaper();
			return;
		}
		if( subject == buttonOpenFile ) {
			OpenFileDialog();
			return;
		}
		if( subject == buttonHilbertCurve ) {
			HilbertCurve();
			return;
		}
		if( subject == buttonText2GCODE ) {
			TextToGCODE();
			return;
		}

		if( subject == buttonStart ) {
			if(gcode.fileOpened && !running) {
				gcode.linesProcessed=0;
				SendLineToRobot("M110 N"+gcode.linesProcessed);
				previewPane.setLinesProcessed(gcode.linesProcessed);
				StartDrawing();
			}
			return;
		}
		if( subject == buttonStartAt ) {
			if(gcode.fileOpened && !running) {
				gcode.linesProcessed=0;
				if(getStartingLineNumber()) {
					SendLineToRobot("M110 N"+gcode.linesProcessed);
					previewPane.setLinesProcessed(gcode.linesProcessed);
					StartDrawing();
				}
			}
			return;
			
		}
		if( subject == buttonPause ) {
			if(running) {
				if(paused==true) {
					penIsUpBeforePause=penIsUp;
					RaisePen();
					buttonPause.setText(MultilingualSupport.getSingleton().get("Pause"));
					paused=false;
					// TODO: if the robot is not ready to unpause, this might fail and the program would appear to hang.
					SendFileCommand();
				} else {
					if(!penIsUpBeforePause) LowerPen();
					buttonPause.setText(MultilingualSupport.getSingleton().get("Unpause"));
					paused=true;
				}
			}
			return;
		}
		if( subject == buttonHalt ) {
			Halt();
			return;
		}
		if( subject == buttonRescan ) {
			ListSerialPorts();
			UpdateMenuBar();
			return;
		}
		if( subject == buttonDisconnect ) {
			ClosePort();
			return;
		}
		if( subject == buttonLoadMachineConfig ) {
			LoadMachineConfig();
			return;
		}
		if( subject == buttonAdjustSounds ) {
			AdjustSounds();
			return;
		}
		if( subject == buttonAdjustGraphics ) {
			AdjustGraphics();
			return;
		}
		if( subject == buttonAdjustLanguage ) {
			MultilingualSupport.getSingleton().ChooseLanguage();
			UpdateMenuBar();
		}
		if( subject == buttonAdjustMachineSize ) {
			MachineConfiguration.getSingleton().AdjustMachineSize();
			previewPane.updateMachineConfig();
			return;
		}
		if( subject == buttonAdjustPulleySize ) {
			MachineConfiguration.getSingleton().AdjustPulleySize();
			previewPane.updateMachineConfig();
			return;
		}
		if( subject == buttonChangeTool ) {
			MachineConfiguration.getSingleton().ChangeTool();
			previewPane.updateMachineConfig();
			return;
		}
		if( subject == buttonAdjustTool ) {
			MachineConfiguration.getSingleton().AdjustTool();
			previewPane.updateMachineConfig();
			return;
		}
		if( subject == buttonJogMotors ) {
			JogMotors();
			return;
		}
		if( subject == buttonAbout ) {
			JOptionPane.showMessageDialog(null,"<html><body>"
					+"<h1>Makelangelo v"+version+"</h1>"
					+"<h3><a href='http://www.marginallyclever.com/'>http://www.marginallyclever.com/</a></h3>"
					+"<p>Created by Dan Royer (dan@marginallyclever.com).<br>Additional contributions by Joseph Cottam.</p><br>"
					+"<p>To get the latest version please visit<br>"
					+"<a href='https://github.com/MarginallyClever/Makelangelo'>https://github.com/MarginallyClever/Makelangelo</a></p><br>"
					+"<p>This program is open source and free.  As it should be!</p>"
					+"</body></html>");
			return;
		}
		if( subject == buttonCheckForUpdate ) {
			CheckForUpdate();
			return;
		}
		
		if( subject == buttonSaveFile ) {
			SaveFileDialog();
			return;
		}
		
		if( subject == buttonExit ) {
			System.exit(0);  // TODO: be more graceful?
			return;
		}
		
		int i;
		for(i=0;i<10;++i) {
			if(subject == buttonRecent[i]) {
				OpenFileOnDemand(recentFiles[i]);
				return;
			}
		}

		for(i=0;i<portsDetected.length;++i) {
			if(subject == buttonPorts[i]) {
				OpenPort(portsDetected[i]);
				return;
			}
		}
		
		if( subject == commandLineSend ) {
			SendLineToRobot(commandLineText.getText());
			commandLineText.setText("");
		}
	}
	
	
	/**
	 * Load a known machine configuration.
	 * Only available if the software is not currently connected to a machine.
	 * 
	 */
	public void LoadMachineConfig() {
		long old_uid = MachineConfiguration.getSingleton().GetUID();
		
		MachineConfiguration.getSingleton().ChooseNewConfig();
		
		long new_uid = MachineConfiguration.getSingleton().GetUID();
		if(old_uid != new_uid) {
			// Force update of graphics layout.
			previewPane.updateMachineConfig();
			previewPane.ZoomToFitPaper();
			// update window title
			mainframe.setTitle(MultilingualSupport.getSingleton().get("TitlePrefix") 
					+ Long.toString(MachineConfiguration.getSingleton().robot_uid) 
					+ MultilingualSupport.getSingleton().get("TitleNotConnected"));
			// TODO offer to regenerate image?
			
		}
	}
	
	
	// Deal with something robot has sent.
	public void serialEvent(SerialPortEvent events) {
        if(events.isRXCHAR()) {
            try {
            	int len = events.getEventValue();
            	byte[] buffer = serialPort.readBytes(len);
				String line2 = new String(buffer,0,len);
				
				serial_recv_buffer+=line2;
				// wait for the cue ("> ") to send another command
				if(serial_recv_buffer.lastIndexOf(cue)!=-1) {
					String line2_mod = serial_recv_buffer.replace("\n", "");
					//line2_mod = line2_mod.replace(">", "");
					line2_mod = line2_mod.trim();
					if(!line2_mod.equals("")) {
						if(line2_mod.lastIndexOf(">")!=-1) {
							if(lastLineWasCue==true) {
								// don't repeat the ping
								//Log("<span style='color:#FF00A5'>"+line2_mod+"</span>");
							} else {
								Log("<span style='color:#FFA500'>"+line2_mod+"</span>");
							}
							lastLineWasCue=true;
						} else {
							lastLineWasCue=false;
							Log("<span style='color:#FFA500'>a"+line2_mod+"b</span>");
						}
					}
					
					int error_line = ErrorReported();
					if(error_line != -1) {
						gcode.linesProcessed = error_line;
						serial_recv_buffer="";
						SendFileCommand();
					} else if(ConfirmPort()) {
						serial_recv_buffer="";
						SendFileCommand();
					}
				}
            } catch (SerialPortException e) {}
        }
    }

	/**
	 * Open the config dialog, update the paper size, refresh the preview tab.
	 */
	public JPanel DriveManually() {
		GridBagConstraints c;
		
		JPanel driver = new JPanel();
		driver.setLayout(new BoxLayout(driver, BoxLayout.PAGE_AXIS));

		JPanel axisControl = new JPanel();
			axisControl.setLayout(new GridBagLayout());
			final JLabel yAxis = new JLabel("Y");			yAxis.setPreferredSize(new Dimension(60,20));		yAxis.setHorizontalAlignment(SwingConstants.CENTER);
			final JButton down100 = new JButton("-100");	down100.setPreferredSize(new Dimension(60,20));
			final JButton down10 = new JButton("-10");		down10.setPreferredSize(new Dimension(60,20));
			final JButton down1 = new JButton("-1");		down1.setPreferredSize(new Dimension(60,20));
			final JButton up1 = new JButton("1");  			up1.setPreferredSize(new Dimension(60,20));
			final JButton up10 = new JButton("10");  		up10.setPreferredSize(new Dimension(60,20));
			final JButton up100 = new JButton("100");  		up100.setPreferredSize(new Dimension(60,20));
			
			final JLabel xAxis = new JLabel("X");			xAxis.setPreferredSize(new Dimension(60,20));		xAxis.setHorizontalAlignment(SwingConstants.CENTER);
			final JButton left100 = new JButton("-100");	left100.setPreferredSize(new Dimension(60,20));
			final JButton left10 = new JButton("-10");		left10.setPreferredSize(new Dimension(60,20));
			final JButton left1 = new JButton("-1");		left1.setPreferredSize(new Dimension(60,20));	
			final JButton right1 = new JButton("1");		right1.setPreferredSize(new Dimension(60,20));
			final JButton right10 = new JButton("10");		right10.setPreferredSize(new Dimension(60,20));
			final JButton right100 = new JButton("100");	right100.setPreferredSize(new Dimension(60,20));

			//final JButton find = new JButton("FIND HOME");	find.setPreferredSize(new Dimension(100,20));
			final JButton center = new JButton(MultilingualSupport.getSingleton().get("SetHome"));	center.setPreferredSize(new Dimension(100,20));
			final JButton home = new JButton(MultilingualSupport.getSingleton().get("GoHome"));		home.setPreferredSize(new Dimension(100,20));
			
			c = new GridBagConstraints();
			//c.fill=GridBagConstraints.BOTH; 
			c.gridx=0;  c.gridy=0;  axisControl.add(yAxis,c);
			c.gridx=1;	c.gridy=0;	axisControl.add(down100,c);
			c.gridx=2;	c.gridy=0;	axisControl.add(down10,c);
			c.gridx=3;	c.gridy=0;	axisControl.add(down1,c);
			c.gridx=4;	c.gridy=0;	axisControl.add(up1,c);
			c.gridx=5;	c.gridy=0;	axisControl.add(up10,c);
			c.gridx=6;	c.gridy=0;	axisControl.add(up100,c);
			
			c.gridx=0;  c.gridy=1;  axisControl.add(xAxis,c);
			c.gridx=1;	c.gridy=1;	axisControl.add(left100,c);
			c.gridx=2;	c.gridy=1;	axisControl.add(left10,c);
			c.gridx=3;	c.gridy=1;	axisControl.add(left1,c);
			c.gridx=4;	c.gridy=1;	axisControl.add(right1,c);
			c.gridx=5;	c.gridy=1;	axisControl.add(right10,c);
			c.gridx=6;	c.gridy=1;	axisControl.add(right100,c);
		
		
		JPanel corners = new JPanel();
			corners.setLayout(new GridBagLayout());
			final JButton goTop = new JButton(MultilingualSupport.getSingleton().get("Top"));		goTop.setPreferredSize(new Dimension(80,20));
			final JButton goBottom = new JButton(MultilingualSupport.getSingleton().get("Bottom"));	goBottom.setPreferredSize(new Dimension(80,20));
			final JButton goLeft = new JButton(MultilingualSupport.getSingleton().get("Left"));		goLeft.setPreferredSize(new Dimension(80,20));
			final JButton goRight = new JButton(MultilingualSupport.getSingleton().get("Right"));	goRight.setPreferredSize(new Dimension(80,20));
			final JButton goUp = new JButton(MultilingualSupport.getSingleton().get("PenUp"));		goUp.setPreferredSize(new Dimension(100,20));
			final JButton goDown = new JButton(MultilingualSupport.getSingleton().get("PenDown"));	goDown.setPreferredSize(new Dimension(100,20));
			c = new GridBagConstraints();
			c.gridx=3;  c.gridy=0;  corners.add(goTop,c);
			c.gridx=3;  c.gridy=1;  corners.add(goBottom,c);
			c.gridx=4;  c.gridy=0;  corners.add(goLeft,c);
			c.gridx=4;  c.gridy=1;  corners.add(goRight,c);
			c.insets = new Insets(0,5,0,0);
			c.gridx=5;  c.gridy=0;  corners.add(goUp,c);
			c.gridx=5;  c.gridy=1;  corners.add(goDown,c);
			c.gridx=6;	c.gridy=0;	corners.add(home,c);
			c.gridx=6;	c.gridy=1;	corners.add(center,c);
			c.insets = new Insets(0,0,0,0);
		
	
		JPanel feedRateControl = new JPanel();
		feedRateControl.setLayout(new GridBagLayout());
			c = new GridBagConstraints();
			feed_rate = MachineConfiguration.getSingleton().GetFeedRate();
			final JFormattedTextField feedRate = new JFormattedTextField(NumberFormat.getInstance());  feedRate.setPreferredSize(new Dimension(100,20));
			feedRate.setText(Double.toString(feed_rate));
			final JButton setFeedRate = new JButton(MultilingualSupport.getSingleton().get("Set"));

			c.gridx=3;  c.gridy=0;  feedRateControl.add(new JLabel(MultilingualSupport.getSingleton().get("Speed")),c);
			c.gridx=4;  c.gridy=0;  feedRateControl.add(feedRate,c);
			c.gridx=5;  c.gridy=0;  feedRateControl.add(new JLabel(MultilingualSupport.getSingleton().get("Rate")),c);
			c.gridx=6;  c.gridy=0;  feedRateControl.add(setFeedRate,c);
		

		driver.add(axisControl);
		driver.add(corners);
		driver.add(feedRateControl);
		
	    JPanel inputField=GetTextInputField();
	    //inputField.setMinimumSize(new Dimension(100,50));
	    //inputField.setMaximumSize(new Dimension(10000,50));

	    driver.add(inputField);
	    
		
		ActionListener driveButtons = new ActionListener() {
			  public void actionPerformed(ActionEvent e) {
					Object subject = e.getSource();
					JButton b = (JButton)subject;
					if(running) return;
					if(b==home) SendLineToRobot("G00 F"+feed_rate+" X0 Y0");
					else if(b==center) SendLineToRobot("G92 X0 Y0");
					else if(b==goLeft) SendLineToRobot("G00 F"+feed_rate+" X"+(MachineConfiguration.getSingleton().paper_left *10));
					else if(b==goRight) SendLineToRobot("G00 F"+feed_rate+" X"+(MachineConfiguration.getSingleton().paper_right*10));
					else if(b==goTop) SendLineToRobot("G00 F"+feed_rate+" Y"+(MachineConfiguration.getSingleton().paper_top*10));
					else if(b==goBottom) SendLineToRobot("G00 F"+feed_rate+" Y"+(MachineConfiguration.getSingleton().paper_bottom*10));
					//} else if(b==find) {
					//	SendLineToRobot("G28");
					else if(b==goUp) RaisePen();
					else if(b==goDown) LowerPen();
					else if(b==setFeedRate) {
						String fr=feedRate.getText();
						fr=fr.replaceAll("[ ,]","");
						feed_rate = Double.parseDouble(fr);
						if(feed_rate<0.001) feed_rate=0.001;
						MachineConfiguration.getSingleton().SetFeedRate(feed_rate);
						feedRate.setText(Double.toString(feed_rate));
						SendLineToRobot("G00 G21 F"+feed_rate);
					} else {
						SendLineToRobot("G91");  // set relative mode

						if(b==down100) SendLineToRobot("G0 Y-100");
						if(b==down10) SendLineToRobot("G0 Y-10");
						if(b==down1) SendLineToRobot("G0 Y-1");
						if(b==up100) SendLineToRobot("G0 Y100");
						if(b==up10) SendLineToRobot("G0 Y10");
						if(b==up1) SendLineToRobot("G0 Y1");

						if(b==left100) SendLineToRobot("G0 X-100");
						if(b==left10) SendLineToRobot("G0 X-10");
						if(b==left1) SendLineToRobot("G0 X-1");
						if(b==right100) SendLineToRobot("G0 X100");
						if(b==right10) SendLineToRobot("G0 X10");
						if(b==right1) SendLineToRobot("G0 X1");
						
						SendLineToRobot("G90");  // return to absolute mode
					}
			  }
		};
		
		up1.addActionListener(driveButtons);
		up10.addActionListener(driveButtons);
		up100.addActionListener(driveButtons);
		down1.addActionListener(driveButtons);
		down10.addActionListener(driveButtons);
		down100.addActionListener(driveButtons);
		left1.addActionListener(driveButtons);
		left10.addActionListener(driveButtons);
		left100.addActionListener(driveButtons);
		right1.addActionListener(driveButtons);
		right10.addActionListener(driveButtons);
		right100.addActionListener(driveButtons);
		goUp.addActionListener(driveButtons);
		goDown.addActionListener(driveButtons);
		center.addActionListener(driveButtons);
		home.addActionListener(driveButtons);
		//find.addActionListener(driveButtons);
		goTop.addActionListener(driveButtons);
		goBottom.addActionListener(driveButtons);
		goLeft.addActionListener(driveButtons);
		goRight.addActionListener(driveButtons);
		setFeedRate.addActionListener(driveButtons);
		
		driver.setPreferredSize(new Dimension(150,100));
		return driver;
	}
	
	protected void JogMotors() {
		JDialog driver = new JDialog(mainframe,MultilingualSupport.getSingleton().get("JogMotors"),true);
		driver.setLayout(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		
		final JButton buttonAneg = new JButton(MultilingualSupport.getSingleton().get("JogIn"));
		final JButton buttonApos = new JButton(MultilingualSupport.getSingleton().get("JogOut"));
		final JCheckBox m1i = new JCheckBox(MultilingualSupport.getSingleton().get("Invert"),MachineConfiguration.getSingleton().m1invert);
		
		final JButton buttonBneg = new JButton(MultilingualSupport.getSingleton().get("JogIn"));
		final JButton buttonBpos = new JButton(MultilingualSupport.getSingleton().get("JogOut"));
		final JCheckBox m2i = new JCheckBox(MultilingualSupport.getSingleton().get("Invert"),MachineConfiguration.getSingleton().m2invert);

		c.gridx=0;	c.gridy=0;	driver.add(new JLabel(MultilingualSupport.getSingleton().get("Left")),c);
		c.gridx=0;	c.gridy=1;	driver.add(new JLabel(MultilingualSupport.getSingleton().get("Right")),c);
		
		c.gridx=1;	c.gridy=0;	driver.add(buttonAneg,c);
		c.gridx=1;	c.gridy=1;	driver.add(buttonBneg,c);
		
		c.gridx=2;	c.gridy=0;	driver.add(buttonApos,c);
		c.gridx=2;	c.gridy=1;	driver.add(buttonBpos,c);

		c.gridx=3;	c.gridy=0;	driver.add(m1i,c);
		c.gridx=3;	c.gridy=1;	driver.add(m2i,c);
		
		ActionListener driveButtons = new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				Object subject = e.getSource();
				if(subject == buttonApos) SendLineToRobot("D00 L100");
				if(subject == buttonAneg) SendLineToRobot("D00 L-100");
				if(subject == buttonBpos) SendLineToRobot("D00 R100");
				if(subject == buttonBneg) SendLineToRobot("D00 R-100");
				SendLineToRobot("M114");
			}
		};

		ActionListener invertButtons = new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				MachineConfiguration.getSingleton().m1invert = m1i.isSelected();
				MachineConfiguration.getSingleton().m2invert = m2i.isSelected();
				
				MachineConfiguration.getSingleton().SaveConfig();
				SendConfig();
			}
		};
		
		buttonApos.addActionListener(driveButtons);
		buttonAneg.addActionListener(driveButtons);
		
		buttonBpos.addActionListener(driveButtons);
		buttonBneg.addActionListener(driveButtons);
		
		m1i.addActionListener(invertButtons);
		m2i.addActionListener(invertButtons);

		SendLineToRobot("M114");
		driver.pack();
		driver.setVisible(true);
	}
	
	public JMenuBar CreateMenuBar() {
        // If the menu bar exists, empty it.  If it doesn't exist, create it.
        menuBar = new JMenuBar();

        UpdateMenuBar();
        
        return menuBar;
	}
	
	public void CheckForUpdate() {
		try {
		    // Get Github info
			URL github = new URL("https://www.marginallyclever.com/other/software-update-check.php?id=1");
	        BufferedReader in = new BufferedReader(new InputStreamReader(github.openStream()));

	        String inputLine;
	        if((inputLine = in.readLine()) != null) {
	        	if( inputLine.compareTo(version) !=0 ) {
	        		JOptionPane.showMessageDialog(null,MultilingualSupport.getSingleton().get("UpdateNotice"));
	        	} else {
	        		JOptionPane.showMessageDialog(null,MultilingualSupport.getSingleton().get("UpToDate"));
	        	}
	        } else {
	        	throw new Exception();
	        }
	        in.close();
		} catch (Exception e) {
			JOptionPane.showMessageDialog(null,MultilingualSupport.getSingleton().get("UpdateCheckFailed"));
		}
	}

	// Rebuild the contents of the menu based on current program state
	public void UpdateMenuBar() {
		JMenu menu, subMenu;
		ButtonGroup group;
        int i;
        
        menuBar.removeAll();
        
        
        // File menu
        menu = new JMenu(MultilingualSupport.getSingleton().get("MenuMakelangelo"));
        menu.setMnemonic(KeyEvent.VK_F);
        menuBar.add(menu);
        
        subMenu = new JMenu(MultilingualSupport.getSingleton().get("MenuPreferences"));
        
        buttonAdjustSounds = new JMenuItem(MultilingualSupport.getSingleton().get("MenuSoundsTitle"));
        buttonAdjustSounds.addActionListener(this);
        subMenu.add(buttonAdjustSounds);

        buttonAdjustGraphics = new JMenuItem(MultilingualSupport.getSingleton().get("MenuGraphicsTitle"));
        buttonAdjustGraphics.addActionListener(this);
        subMenu.add(buttonAdjustGraphics);

        buttonAdjustLanguage = new JMenuItem(MultilingualSupport.getSingleton().get("MenuLanguageTitle"));
        buttonAdjustLanguage.addActionListener(this);
        subMenu.add(buttonAdjustLanguage);
        menu.add(subMenu);
        
        buttonCheckForUpdate = new JMenuItem(MultilingualSupport.getSingleton().get("MenuUpdate"),KeyEvent.VK_U);
        buttonCheckForUpdate.addActionListener(this);
        buttonCheckForUpdate.setEnabled(true);
        menu.add(buttonCheckForUpdate);
        
        buttonAbout = new JMenuItem(MultilingualSupport.getSingleton().get("MenuAbout"),KeyEvent.VK_A);
        buttonAbout.addActionListener(this);
        menu.add(buttonAbout);

        menu.addSeparator();
        
        buttonExit = new JMenuItem(MultilingualSupport.getSingleton().get("MenuQuit"),KeyEvent.VK_Q);
        buttonExit.addActionListener(this);
        menu.add(buttonExit);
        
        
        // Connect menu
        subMenu = new JMenu(MultilingualSupport.getSingleton().get("MenuConnect"));
        subMenu.setEnabled(!running);
        group = new ButtonGroup();

        ListSerialPorts();
        buttonPorts = new JRadioButtonMenuItem[portsDetected.length];
        for(i=0;i<portsDetected.length;++i) {
        	buttonPorts[i] = new JRadioButtonMenuItem(portsDetected[i]);
            if(recentPort.equals(portsDetected[i]) && portOpened) {
            	buttonPorts[i].setSelected(true);
            }
            buttonPorts[i].addActionListener(this);
            group.add(buttonPorts[i]);
            subMenu.add(buttonPorts[i]);
        }
        
        subMenu.addSeparator();

        buttonRescan = new JMenuItem(MultilingualSupport.getSingleton().get("MenuRescan"),KeyEvent.VK_N);
        buttonRescan.addActionListener(this);
        subMenu.add(buttonRescan);

        buttonDisconnect = new JMenuItem(MultilingualSupport.getSingleton().get("MenuDisconnect"),KeyEvent.VK_D);
        buttonDisconnect.addActionListener(this);
        buttonDisconnect.setEnabled(portOpened);
        subMenu.add(buttonDisconnect);
        
        menuBar.add(subMenu);

        // settings menu
        menu = new JMenu(MultilingualSupport.getSingleton().get("MenuSettings"));
        menu.setMnemonic(KeyEvent.VK_T);
        menu.setEnabled(!running);

        if(MachineConfiguration.getSingleton().GetMachineCount() > 1) {
	        buttonLoadMachineConfig = new JMenuItem(MultilingualSupport.getSingleton().get("MenuLoadMachineConfig"));
	        buttonLoadMachineConfig.addActionListener(this);
	        buttonLoadMachineConfig.setEnabled(!portOpened);
	        menu.add(buttonLoadMachineConfig);
	        
	        menu.addSeparator();
        }
        
        buttonAdjustMachineSize = new JMenuItem(MultilingualSupport.getSingleton().get("MenuSettingsMachine"),KeyEvent.VK_L);
        buttonAdjustMachineSize.addActionListener(this);
        buttonAdjustMachineSize.setEnabled(!running);
        menu.add(buttonAdjustMachineSize);

        buttonAdjustPulleySize = new JMenuItem(MultilingualSupport.getSingleton().get("MenuAdjustPulleys"),KeyEvent.VK_B);
        buttonAdjustPulleySize.addActionListener(this);
        buttonAdjustPulleySize.setEnabled(!running);
        menu.add(buttonAdjustPulleySize);
        
        buttonJogMotors = new JMenuItem(MultilingualSupport.getSingleton().get("JogMotors"),KeyEvent.VK_J);
        buttonJogMotors.addActionListener(this);
        buttonJogMotors.setEnabled(portConfirmed && !running);
        menu.add(buttonJogMotors);

        menu.addSeparator();
        
        buttonChangeTool = new JMenuItem(MultilingualSupport.getSingleton().get("MenuSelectTool"),KeyEvent.VK_T);
        buttonChangeTool.addActionListener(this);
        buttonChangeTool.setEnabled(!running);
        menu.add(buttonChangeTool);

        buttonAdjustTool = new JMenuItem(MultilingualSupport.getSingleton().get("MenuAdjustTool"),KeyEvent.VK_B);
        buttonAdjustTool.addActionListener(this);
        buttonAdjustTool.setEnabled(!running);
        menu.add(buttonAdjustTool);
        
        menuBar.add(menu);

        

        // File conversion menu
        menu = new JMenu(MultilingualSupport.getSingleton().get("MenuGCODE"));
        menu.setMnemonic(KeyEvent.VK_H);
        
        subMenu = new JMenu(MultilingualSupport.getSingleton().get("MenuConvertImage"));
        subMenu.setEnabled(!running);
        group = new ButtonGroup();

	        buttonOpenFile = new JMenuItem(MultilingualSupport.getSingleton().get("MenuOpenFile"),KeyEvent.VK_O);
	        buttonOpenFile.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, ActionEvent.ALT_MASK));
	        buttonOpenFile.addActionListener(this);
	        subMenu.add(buttonOpenFile);

	        // list recent files
	        if(recentFiles != null && recentFiles.length>0) {
	        	// add a separator only if there are recent files
	        	if( recentFiles.length!=0 ) subMenu.addSeparator();
	        	
	        	for(i=0;i<recentFiles.length;++i) {
	        		if(recentFiles[i] == null || recentFiles[i].length()==0) break;
	            	buttonRecent[i] = new JMenuItem((1+i) + " "+recentFiles[i],KeyEvent.VK_1+i);
	            	if(buttonRecent[i]!=null) {
	            		buttonRecent[i].addActionListener(this);
	            		subMenu.add(buttonRecent[i]);
	            	}
	        	}
	        }
        
        menu.add(subMenu);

        buttonHilbertCurve = new JMenuItem(MultilingualSupport.getSingleton().get("MenuHilbertCurve"));
        buttonHilbertCurve.setEnabled(!running);
        buttonHilbertCurve.addActionListener(this);
        menu.add(buttonHilbertCurve);
        
        buttonText2GCODE = new JMenuItem(MultilingualSupport.getSingleton().get("MenuTextToGCODE"));
        buttonText2GCODE.setEnabled(!running);
        buttonText2GCODE.addActionListener(this);
        menu.add(buttonText2GCODE);

        buttonSaveFile = new JMenuItem(MultilingualSupport.getSingleton().get("MenuSaveGCODEAs"));
        buttonSaveFile.addActionListener(this);
        menu.add(buttonSaveFile);

        menuBar.add(menu);
        
        
        
        // Draw menu
        menu = new JMenu(MultilingualSupport.getSingleton().get("MenuDraw"));

        buttonStart = new JMenuItem(MultilingualSupport.getSingleton().get("Start"),KeyEvent.VK_S);
        buttonStart.addActionListener(this);
    	buttonStart.setEnabled(portConfirmed && !running);
        menu.add(buttonStart);

        buttonStartAt = new JMenuItem(MultilingualSupport.getSingleton().get("StartAtLine"));
        buttonStartAt.addActionListener(this);
        buttonStartAt.setEnabled(portConfirmed && !running);
        menu.add(buttonStartAt);

        buttonPause = new JMenuItem(MultilingualSupport.getSingleton().get("Pause"),KeyEvent.VK_P);
        buttonPause.addActionListener(this);
        buttonPause.setEnabled(portConfirmed && running);
        menu.add(buttonPause);

        buttonHalt = new JMenuItem(MultilingualSupport.getSingleton().get("Halt"),KeyEvent.VK_H);
        buttonHalt.addActionListener(this);
        buttonHalt.setEnabled(portConfirmed && running);
        menu.add(buttonHalt);

        menuBar.add(menu);
        
        // view menu
        menu = new JMenu(MultilingualSupport.getSingleton().get("MenuPreview"));
        buttonZoomOut = new JMenuItem(MultilingualSupport.getSingleton().get("ZoomOut"));
        buttonZoomOut.addActionListener(this);
        buttonZoomOut.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS,ActionEvent.ALT_MASK));
        menu.add(buttonZoomOut);
        
        buttonZoomIn = new JMenuItem(MultilingualSupport.getSingleton().get("ZoomIn"),KeyEvent.VK_EQUALS);
        buttonZoomIn.addActionListener(this);
        buttonZoomIn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS,ActionEvent.ALT_MASK));
        menu.add(buttonZoomIn);
        
        buttonZoomToFit = new JMenuItem(MultilingualSupport.getSingleton().get("ZoomFit"));
        buttonZoomToFit.addActionListener(this);
        menu.add(buttonZoomToFit);
        
        menuBar.add(menu);

        // finish
        menuBar.updateUI();
    }

	// manages the vertical split in the GUI
	public class Splitter extends JSplitPane {
		static final long serialVersionUID=1;
		
		public Splitter(int split_direction) {
			super(split_direction);
			setResizeWeight(0.9);
			setDividerLocation(0.9);
		}
	}
	
    public Container CreateContentPane() {
        //Create the content-pane-to-be.
        JPanel contentPane = new JPanel(new BorderLayout());
        contentPane.setOpaque(true);
        
        // the log panel
        log = new JTextPane();
        log.setEditable(false);
        log.setBackground(Color.BLACK);
        logPane = new JScrollPane(log);
        kit = new HTMLEditorKit();
        doc = new HTMLDocument();
        log.setEditorKit(kit);
        log.setDocument(doc);
        DefaultCaret c = (DefaultCaret)log.getCaret();
        c.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        ClearLog();
        
        // the preview panel
        previewPane = new DrawPanel();
        
        // the drive panel
        drivePane = DriveManually();
        
        // status bar
        statusBar = new StatusBar();
        Font f = statusBar.getFont();
        statusBar.setFont(f.deriveFont(Font.BOLD,15));
        Dimension d=statusBar.getMinimumSize();
        d.setSize(d.getWidth(), d.getHeight()+30);
        statusBar.setMinimumSize(d);

        // layout
        Splitter drive_and_preview = new Splitter(JSplitPane.VERTICAL_SPLIT);
        drive_and_preview.add(logPane);
        drive_and_preview.add(drivePane);
        //drive_and_preview.setDividerSize(8);
        //drive_and_preview.setDividerLocation(-100);
        
        Splitter split = new Splitter(JSplitPane.HORIZONTAL_SPLIT);
        split.add(previewPane);
        split.add(drive_and_preview);
        //split.setDividerSize(8);
        //split.setDividerLocation(-10);

        contentPane.add(statusBar,BorderLayout.SOUTH);
        contentPane.add(split,BorderLayout.CENTER);
		
        return contentPane;
    }


	// connect to the last port
    private void reconnectToLastPort() {
	    ListSerialPorts();
		if(Arrays.asList(portsDetected).contains(recentPort)) {
			OpenPort(recentPort);
		}
	}
    
    // if the default file being opened in a g-code file, this is ok.  Otherwise it may take too long and look like a crash/hang.
    private void reopenLastFile() {
		if(recentFiles[0].length()>0) {
			OpenFileOnDemand(recentFiles[0]);
		}
    }

	private JPanel GetTextInputField() {
		textInputArea = new JPanel();
		textInputArea.setLayout(new BoxLayout(textInputArea,BoxLayout.LINE_AXIS));
		
		commandLineText = new JTextField(0);
		commandLineText.setPreferredSize(new Dimension(10,10));
		commandLineSend = new JButton(MultilingualSupport.getSingleton().get("Send"));
		
		textInputArea.add(commandLineText);
		textInputArea.add(commandLineSend);
		
		commandLineText.addKeyListener(this);
		commandLineSend.addActionListener(this);
		
		return textInputArea;
	}

    public JFrame getParentFrame() {
    	return mainframe;
    }
    
    
    // Create the GUI and show it.  For thread safety, this method should be invoked from the event-dispatching thread.
    private static void CreateAndShowGUI() {
    	// Check language preferences
    	MultilingualSupport.getSingleton();
    	
        // Create and set up the window.
    	mainframe = new JFrame("Makelangelo");
        mainframe.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        // Create and set up the content pane.
        Makelangelo m = Makelangelo.getSingleton();
        mainframe.setJMenuBar(m.CreateMenuBar());
        mainframe.setContentPane(m.CreateContentPane());
 
        // Display the window.
        int width =m.prefs.getInt("Default window width", 1200);
        int height=m.prefs.getInt("Default window height", 700);
        mainframe.setSize(width,height);
        mainframe.setVisible(true);
        
        m.previewPane.ZoomToFitPaper();
        
        if(m.prefs.getBoolean("Reconnect to last port on start", false)) m.reconnectToLastPort();
        if(m.prefs.getBoolean("Open last file on start", false)) m.reopenLastFile();
        if(m.prefs.getBoolean("Check for updates", false)) m.CheckForUpdate();
    }
    
    public static void main(String[] args) {
	    //Schedule a job for the event-dispatching thread:
	    //creating and showing this application's GUI.
	    javax.swing.SwingUtilities.invokeLater(new Runnable() {
	        public void run() {
	        	/*
	        	String OS = System.getProperty("os.name").toLowerCase();
	            String workingDirectory=System.getProperty("user.dir");
	            System.out.println(workingDirectory);
	            
	            System.out.println(OS);
	            // is this Windows?
	            if(OS.indexOf("win") >= 0) {
	            	// is 64 bit?
	            	if(System.getenv("ProgramFiles(x86)") != null) {
	            		// 64 bit
	            		System.load(workingDirectory+"/64/rxtxSerial.dll");
	            	} else {
	            		// 32 bit
	            		System.load(workingDirectory+"/32/rxtxSerial.dll");
	            	}
	            } else {
	            	// is this OSX?
	    	        if(OS.indexOf("mac") >= 0) {
	    	    		System.load(workingDirectory+"/librxtxSerial.jnilib");
	    	        }
	            }
	    		*/
	            CreateAndShowGUI();
	        }
	    });
    }
}


/**
 * This file is part of DrawbotGUI.
 *
 * DrawbotGUI is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * DrawbotGUI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Foobar.  If not, see <http://www.gnu.org/licenses/>.
 */
