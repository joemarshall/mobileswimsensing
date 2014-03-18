package com.joemarshall.swimcounter;

// TODO: javascript error handling

import java.io.File;
import java.util.Locale;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Toast;

import com.joemarshall.swimcounter.SwimMetricExtractor.Callback;
import com.joemarshall.swimcounter.SwimMetricExtractor.EventPoint;
import com.joemarshall.swimcounter.SwimMetricExtractor.EventType;
import com.joemarshall.swimcounter.SwimMetricExtractor.LengthStatistics;
import com.joemarshall.swimcounter.SwimMetricExtractor.State;

public class LiveCounterActivity extends Activity implements
		SensorEventListener, Callback, SocketReplay.Callback
{
	SwimMetricExtractor m_Extractor = new SwimMetricExtractor(this);
	WebView m_WebView;
	SocketReplay m_SocketReplay;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_live_counter);
		/*
		 * File fromLog = new File(Environment.getExternalStorageDirectory(),
		 * "test.csv"); if (fromLog.exists() && fromLog.canRead()) {
		 * processLog(fromLog); }
		 */
		initialiseWebView();
		//registerSensorListeners();
		m_SocketReplay=new SocketReplay(this);
	}

	String m_JavascriptCallbackName = null;

	class JsObject
	{
		@JavascriptInterface
		public void setCallback(String name)
		{
			name.replaceAll("\\W", "");
			m_JavascriptCallbackName = name;
			Log.e("js", name);
		}
	};

	class BrowserCallback extends WebChromeClient
	{
		public boolean onConsoleMessage(ConsoleMessage msg)
		{
			Log.e("js",
					msg.sourceId() + "(" + msg.lineNumber() + "):"
							+ msg.message());
			return true;
		}
	};

	@SuppressLint("SetJavaScriptEnabled")
	private void initialiseWebView()
	{
		m_WebView = (WebView) findViewById(R.id.mainWebView);
		m_WebView.getSettings().setJavaScriptEnabled(true);
		m_WebView.addJavascriptInterface(new JsObject(), "swimMetrics");
		m_WebView.setWebChromeClient(new BrowserCallback());

		m_WebView.loadUrl("file:///android_asset/numbersview.html");
	}

	@SuppressWarnings("unused")
	private void processLog(File path)
	{
		File outFile = new File(path.getParent(), "output.txt");
		Toast.makeText(LiveCounterActivity.this, "Processing csv",
				Toast.LENGTH_LONG).show();
		new LogRunner(this).execute(path.getAbsolutePath(),
				outFile.getAbsolutePath());
	}

	@Override
	protected void onDestroy()
	{
		m_SocketReplay.cancel();
		unregisterSensorListeners();
		super.onDestroy();
	}

	private SensorManager m_SensorManager = null;
	private Sensor m_LinearAcceleration;
	private Sensor m_RotationVector;

	private float[] m_MagneticFieldCache = new float[3];

	protected void registerIfNonNull(Sensor s)
	{
		if (s != null)
		{
			m_SensorManager.registerListener(this, s,
					SensorManager.SENSOR_DELAY_FASTEST);
		}
	}

	protected void registerSensorListeners()
	{
		m_SensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		m_RotationVector = m_SensorManager
				.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
		m_LinearAcceleration = m_SensorManager
				.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
		if (m_RotationVector == null)
		{
			m_RotationVector = m_SensorManager
					.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
		}
		if (m_LinearAcceleration == null)
		{
			m_LinearAcceleration = m_SensorManager
					.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		}

		registerIfNonNull(m_LinearAcceleration);
		registerIfNonNull(m_RotationVector);
	}

	protected void unregisterSensorListeners()
	{
		if(m_SensorManager!=null)
		{
			m_SensorManager.unregisterListener(this);
		}
	}

	@Override
	public void onAccuracyChanged(Sensor arg0, int arg1)
	{

	}
	
	public void useNetworkSensors()
	{
		if(!usingNetworkSensors)
		{
			usingNetworkSensors=true;
			unregisterSensorListeners();
			Log.e("socket","using network sensors");
		}
	}

	
	int valuesBeforeFilterStability=50;
	float[] m_RotateMatrix = new float[9];
	float[] m_Orientation = new float[3];

	float[] filter_gravity = new float[3];
	float[] filter_linear_accel = new float[3];

	void filterAccelerometer(float[] values)
	{
		// In this example, alpha is calculated as t / (t + dT),
		// where t is the low-pass filter's time-constant and
		// dT is the event delivery rate.

		final float alpha = 0.95f;

		// Isolate the force of gravity with the low-pass filter.
		filter_gravity[0] = alpha * filter_gravity[0] + (1 - alpha)
				* values[0];
		filter_gravity[1] = alpha * filter_gravity[1] + (1 - alpha)
				* values[1];
		filter_gravity[2] = alpha * filter_gravity[2] + (1 - alpha)
				* values[2];

		// Remove the gravity contribution with the high-pass filter.
		filter_linear_accel[0] = values[0] - filter_gravity[0];
		filter_linear_accel[1] = values[1] - filter_gravity[1];
		filter_linear_accel[2] = values[2] - filter_gravity[2];
	}

	long lastDisplayTimestamp = 0L;

	@Override
	public void onSensorChanged(SensorEvent event)
	{
		onSensorValues(event.sensor.getType(),event.values,event.timestamp);
	}
	
	public static final int PREPROCESSED_ORIENTATION=-1;
	
	public void onSensorValues(int type,float []values,long timestamp)
	{
		switch (type)
		{
		case Sensor.TYPE_LINEAR_ACCELERATION:
			m_Extractor.onLinearAcceleration(timestamp, values[0],
					values[1], values[2]);
			break;
		case PREPROCESSED_ORIENTATION:
			m_Extractor.onOrientationChange(timestamp, values[0],
					values[1],values[2]);
			break;
		case Sensor.TYPE_ROTATION_VECTOR:
			SensorManager.getRotationMatrixFromVector(m_RotateMatrix,
					values);
			SensorManager.getOrientation(m_RotateMatrix, m_Orientation);
			m_Extractor.onOrientationChange(timestamp, m_Orientation[0],
					m_Orientation[1], m_Orientation[2]);
			break;
		case Sensor.TYPE_MAGNETIC_FIELD:
			System.arraycopy(values, 0, m_MagneticFieldCache, 0, 3);
			break;
		case Sensor.TYPE_ACCELEROMETER:
			filterAccelerometer(values);
			if(valuesBeforeFilterStability>0)
			{
				valuesBeforeFilterStability-=1;
				return;
			}
			m_Extractor.onGlobalAcceleration(timestamp, values[0],
					values[1], values[2]);
			// double accelMagnitude = Math
			// .sqrt((event.values[0] * event.values[0])
			// / + (event.values[1] * event.values[1])
			// + (event.values[2] * event.values[2]));
			// if (Math.abs(accelMagnitude - SensorManager.GRAVITY_EARTH) < 1.0)
			// {
			SensorManager.getRotationMatrix(m_RotateMatrix, null,
					filter_gravity, m_MagneticFieldCache);
			SensorManager.getOrientation(m_RotateMatrix, m_Orientation);
			m_Extractor.onOrientationChange(timestamp, m_Orientation[0],
					m_Orientation[1], m_Orientation[2]);
			// }
			break;

		}

		if (timestamp - lastDisplayTimestamp > 50000000)
		{
			State state = m_Extractor.getState();
			updateDisplay(state);
			lastDisplayTimestamp = timestamp;
		}
	}

	private void updateDisplay(State state)
	{
		if(m_JavascriptCallbackName!=null && (m_TimingState==TimingState.TIMING_STARTED || m_TimingState==TimingState.TIMING_AUTOSTOP))
		{
			long totalTime=state.lastTimestamp-totalTimeStartTimestamp;
			long swimTime=swimTimeAtStartOfLength+state.timeInLength;

			if(swimTime<lastSwimTime)swimTime=lastSwimTime;
			lastSwimTime=swimTime;
			String jsCallString=String.format(Locale.UK,"javascript:%s.onIntermediateTimes(%f,%f,%d,%f)", m_JavascriptCallbackName,((double) totalTime) / 1000000000.0,((double) swimTime) / 1000000000.0,lengthsCounted,((double) state.timeInLength) / 1000000000.0);
//			Log.e("js",m_WebView.getProgress()+":"+jsCallString);
			m_WebView.loadUrl(jsCallString);
		}		
	}

	@Override
	public void onLengthComplete(LengthStatistics stats)
	{
		if(m_TimingState!=TimingState.TIMING_STOPPED)
		{
			State state = m_Extractor.getState();
			lengthsCounted+=1;		
			swimTimeAtStartOfLength+=stats.lengthTime;			
			if(m_JavascriptCallbackName!=null)
			{
				long totalTime=state.lastTimestamp-totalTimeStartTimestamp;
				String jsCallString=String.format(Locale.UK,"javascript:%s.onLengthDone(%f,%f,%d,%f,%d,\"%s\",\"%s\")", m_JavascriptCallbackName,((double) totalTime) / 1000000000.0,((double) swimTimeAtStartOfLength) / 1000000000.0,lengthsCounted,((double) stats.lengthTime) / 1000000000.0, stats.strokes,
						stats.stroke.toString(),stats.turnType.toString());				
				m_WebView.loadUrl(jsCallString);				
			}
		}		
		Log.v("length", String.format("%f,%d,%s,%s",
				((double) stats.lengthTime) / 1000000000.0, stats.strokes,
				stats.stroke.toString(), stats.turnType.toString()));
		if(m_TimingState==TimingState.TIMING_AUTOSTART)
		{
			m_TimingState=TimingState.TIMING_STOPPED;
		}
			
	}

	@Override
	public void logError(String tag, String value)
	{
		Log.e(tag, value);
	}

	@Override
	public void logInfo(String tag, String value)
	{
		Log.v(tag, value);
	}

	@Override
	public void onEvent(EventPoint event)
	{	
		Log.e("evt",event.m_Type+":"+event.m_Value);
		if(m_TimingState==TimingState.TIMING_AUTOSTART && event.m_Type==EventType.EVENT_START)
		{
			m_TimingState=TimingState.TIMING_STARTED;
			totalTimeStartTimestamp=event.timestamp;			
		}
		if(m_TimingState!=TimingState.TIMING_STOPPED)
		{
			if(m_JavascriptCallbackName!=null)
			{
				State state = m_Extractor.getState();
				long totalTime=state.lastTimestamp-totalTimeStartTimestamp;
				long swimTime=swimTimeAtStartOfLength+state.timeInLength;
				if(swimTime<lastSwimTime)swimTime=lastSwimTime;
				lastSwimTime=swimTime;
				String jsCallString=String.format(Locale.UK,"javascript:%s.onIntermediateData(%f,%f,%d,%f,%d,\"%s\")", m_JavascriptCallbackName,((double) totalTime) / 1000000000.0,((double) swimTime) / 1000000000.0,lengthsCounted,((double) state.timeInLength) / 1000000000.0, state.count,
						state.stroke.toString());				
				m_WebView.loadUrl(jsCallString);
			}
			
		}
	}
	
	

	/*
	 * @Override public boolean onCreateOptionsMenu(Menu menu) { // Inflate the
	 * menu; this adds items to the action bar if it is present.
	 * getMenuInflater().inflate(R.menu.live_counter, menu); return true; }
	 */

	// stuff to do with the overall timers & counters
	// ie. how long have they swum so far, how many lengths,
	// counting swimming vs stopped time
	enum TimingState
	{
		TIMING_STOPPED,//not timing or counting lengths
		TIMING_AUTOSTART,// start timer when they start swimming 
		TIMING_STARTED,//timing
		TIMING_AUTOSTOP,// stop timer when swimming stops
	};
	TimingState m_TimingState=TimingState.TIMING_AUTOSTART;
	long totalTimeStartTimestamp=0;
	long swimTimeAtStartOfLength=0;
	long lastSwimTime=0;
	
	int lengthsCounted=0;
	boolean usingNetworkSensors=false;

	float[] netValues=new float[3];
	@Override
	public void onNetworkSensor(int type, float x, float y, float z,long timestamp)
	{
		netValues[0]=x;
		netValues[1]=y;
		netValues[2]=z;
		useNetworkSensors();
		onSensorValues(type,netValues,timestamp);		
	}

	@Override
	public void onNetworkConnect()
	{
		m_Extractor = new SwimMetricExtractor(this);
		m_TimingState=TimingState.TIMING_AUTOSTART;
		lastDisplayTimestamp=0L;
		swimTimeAtStartOfLength=0;
		totalTimeStartTimestamp=0;
		lastSwimTime=0;
	}

	@Override
	public void onNetworkDisconnect()
	{
		
	}

}
