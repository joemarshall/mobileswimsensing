package com.joemarshall.swimcounter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;

import com.joemarshall.swimcounter.SwimMetricExtractor.Callback;
import com.joemarshall.swimcounter.SwimMetricExtractor.EventPoint;
import com.joemarshall.swimcounter.SwimMetricExtractor.LengthStatistics;
import com.joemarshall.swimcounter.SwimMetricExtractor.State;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import android.media.MediaScannerConnection;
import android.media.MediaScannerConnection.OnScanCompletedListener;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.app.Activity;
import android.content.Context;
import android.util.Log;

import android.widget.TextView;
import android.widget.Toast;

public class LiveCounterActivity extends Activity implements
		SensorEventListener, Callback
{
	SwimMetricExtractor m_Extractor = new SwimMetricExtractor(this);

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_live_counter);
		/*		File fromLog = new File(Environment.getExternalStorageDirectory(),
				"test.csv");
		if (fromLog.exists() && fromLog.canRead())
		{
			processLog(fromLog);
		}*/
		registerSensorListeners();
	}

	class LogRunner extends AsyncTask<String,Integer,Long> implements Callback
	{
		SwimMetricExtractor m_LogExtractor= new SwimMetricExtractor(this);
		
		FileOutputStream m_OutputStream;

		@Override
		public void onLengthComplete(LengthStatistics stats)
		{
			String str="length,"+String.format("%f,%d,%s,%b",
					((double) stats.lengthTime) / 1000000000.0, stats.strokes,
					stats.stroke.toString(), stats.turned)+"\r\n";
			try
			{
				m_OutputStream.write(str.getBytes());
			} catch (IOException e)
			{
				e.printStackTrace();
			}
		}
		@Override
		public void onEvent(EventPoint event)
		{
			String str="evt,"+event.m_Type.toString() + "," + event.m_Value+"\r\n";
			try
			{
				m_OutputStream.write(str.getBytes());
			} catch (IOException e)
			{
				e.printStackTrace();
			}			
		}

		@Override
		public void logError(String tag, String value)
		{
			Log.e(tag,value);
		}

		@Override
		public void logInfo(String tag, String value)
		{
			String str="log,"+tag + "," + value+"\r\n";
			try
			{
				m_OutputStream.write(str.getBytes());
			} catch (IOException e)
			{
				e.printStackTrace();
			}						
		}


		@Override
		protected Long doInBackground(String... params)
		{
			String fromFile=params[0];
			String toFile=params[1];
			try
			{
				BufferedReader br = new BufferedReader(new FileReader(fromFile));
				m_OutputStream=new FileOutputStream(toFile);
				String line;
				// skip header line
				line=br.readLine();
				while( (line=br.readLine())!=null)
				{
					String[] values=line.split(",");
					if(values.length>17)
					{
						long eventTime=Long.parseLong(values[1]);
						float linX=Float.parseFloat(values[6]);
						float linY=Float.parseFloat(values[7]);
						float linZ=Float.parseFloat(values[8]);
						float oriX=Float.parseFloat(values[15]);
						float oriY=Float.parseFloat(values[16]);
						float oriZ=Float.parseFloat(values[17]);
						m_LogExtractor.onLinearAcceleration(eventTime, linX, linY,linZ);
						m_LogExtractor.onOrientationChange(eventTime, oriX,oriY,oriZ);
					}
				}
				br.close();
				
				String[]paths={toFile};
				String[]mimeTypes={null};
				MediaScannerConnection.scanFile(LiveCounterActivity.this, paths, mimeTypes, null);
				
			} catch (FileNotFoundException e)
			{
				e.printStackTrace();
				return Long.valueOf(-1);
			} catch (IOException e)
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
				return Long.valueOf(-2);
			}
			
			// read a point
			// update the orientation and linear accel in m_LogExtractor
			return Long.valueOf(0);
		}
		
		 protected void onPostExecute(Long result) {
			 Toast.makeText(LiveCounterActivity.this, "Processed csv:"+result.toString(), Toast.LENGTH_LONG).show();
	     }		
		
	}
	
	private void processLog(File path)
	{
		File outFile=new File(path.getParent(),"output.txt");
		Toast.makeText(LiveCounterActivity.this, "Processing csv", Toast.LENGTH_LONG).show();
		new LogRunner().execute(path.getAbsolutePath(),outFile.getAbsolutePath());
	}

	@Override
	protected void onDestroy()
	{
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
		m_SensorManager.unregisterListener(this);
	}

	@Override
	public void onAccuracyChanged(Sensor arg0, int arg1)
	{

	}

	float[] m_RotateMatrix = new float[9];
	float[] m_Orientation = new float[3];

	float[] filter_gravity = new float[3];
	float[] filter_linear_accel = new float[3];

	void filterAccelerometer(SensorEvent event)
	{
		// In this example, alpha is calculated as t / (t + dT),
		// where t is the low-pass filter's time-constant and
		// dT is the event delivery rate.

		final float alpha = 0.95f;

		// Isolate the force of gravity with the low-pass filter.
		filter_gravity[0] = alpha * filter_gravity[0] + (1 - alpha)
				* event.values[0];
		filter_gravity[1] = alpha * filter_gravity[1] + (1 - alpha)
				* event.values[1];
		filter_gravity[2] = alpha * filter_gravity[2] + (1 - alpha)
				* event.values[2];

		// Remove the gravity contribution with the high-pass filter.
		filter_linear_accel[0] = event.values[0] - filter_gravity[0];
		filter_linear_accel[1] = event.values[1] - filter_gravity[1];
		filter_linear_accel[2] = event.values[2] - filter_gravity[2];
	}

	@Override
	public void onSensorChanged(SensorEvent event)
	{
		switch (event.sensor.getType())
		{
		case Sensor.TYPE_LINEAR_ACCELERATION:
			m_Extractor.onLinearAcceleration(event.timestamp, event.values[0],
					event.values[1], event.values[2]);
			break;
		case Sensor.TYPE_ROTATION_VECTOR:
			SensorManager.getRotationMatrixFromVector(m_RotateMatrix,
					event.values);
			SensorManager.getOrientation(m_RotateMatrix, m_Orientation);
			m_Extractor.onOrientationChange(event.timestamp, m_Orientation[0],
					m_Orientation[1], m_Orientation[2]);
			break;
		case Sensor.TYPE_MAGNETIC_FIELD:
			System.arraycopy(event.values, 0, m_MagneticFieldCache, 0, 3);
			break;
		case Sensor.TYPE_ACCELEROMETER:
			filterAccelerometer(event);
			m_Extractor.onGlobalAcceleration(event.timestamp, event.values[0],
					event.values[1], event.values[2]);
			// double accelMagnitude = Math
			// .sqrt((event.values[0] * event.values[0])
			// / + (event.values[1] * event.values[1])
			// + (event.values[2] * event.values[2]));
			// if (Math.abs(accelMagnitude - SensorManager.GRAVITY_EARTH) < 1.0)
			// {
			SensorManager.getRotationMatrix(m_RotateMatrix, null,
					filter_gravity, m_MagneticFieldCache);
			SensorManager.getOrientation(m_RotateMatrix, m_Orientation);
			m_Extractor.onOrientationChange(event.timestamp, m_Orientation[0],
					m_Orientation[1], m_Orientation[2]);
			// }
			break;

		}

		State state = m_Extractor.getState();
		updateDisplay(state);
	}

	private void updateDisplay(State state)
	{
		String strokeName = "";
		switch (state.stroke)
		{
		case STROKE_BACK:
			strokeName = "Back";
			break;
		case STROKE_BREAST:
			strokeName = "Breast";
			break;
		case STROKE_BUTTERFLY:
			strokeName = "Butterfly";
			break;
		case STROKE_CRAWL:
			strokeName = "Crawl";
			break;
		case STROKE_UNKNOWN:
			strokeName = "Unknown";
			break;
		default:
			break;
		}
		((TextView) findViewById(R.id.swim_style)).setText(strokeName);
		String strokeCount = "";
		// if (state.swimming != SwimState.SWIMMING_NOT)
		// {
		strokeCount = Integer.toString(state.count);
		// }
		((TextView) findViewById(R.id.swim_strokes)).setText(strokeCount);
		double value = ((double) state.timeInLength) / 1000000000.0;
		((TextView) findViewById(R.id.swim_time)).setText(String.format(
				"%2.2f", value));

		((TextView) findViewById(R.id.debug1)).setText(String.format(
				"%2.2f,%2.2f,%2.2f", m_Orientation[0], m_Orientation[1],
				m_Orientation[2]));
		// ((TextView) findViewById(R.id.debug1)).setText(String.format(
		// "%2.2f,%2.2f,%2.2f", filter_gravity[0], filter_gravity[1],
		// filter_gravity[2]));
		((TextView) findViewById(R.id.debug2)).setText(state.debugVals);
	}

	@Override
	public void onLengthComplete(LengthStatistics stats)
	{
		Log.v("length", String.format("%f,%d,%s,%b",
				((double) stats.lengthTime) / 1000000000.0, stats.strokes,
				stats.stroke.toString(), stats.turned));
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
		Log.v("evt", event.m_Type.toString() + "," + event.m_Value);
	}

	/*
	 * @Override public boolean onCreateOptionsMenu(Menu menu) { // Inflate the
	 * menu; this adds items to the action bar if it is present.
	 * getMenuInflater().inflate(R.menu.live_counter, menu); return true; }
	 */

}
