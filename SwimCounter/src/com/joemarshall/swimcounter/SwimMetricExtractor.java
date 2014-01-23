package com.joemarshall.swimcounter;

import java.util.ArrayList;

import android.hardware.SensorManager;
import android.util.Log;

public class SwimMetricExtractor
{
	enum SwimState
	{
		SWIMMING_NOT, SWIMMING_MAYBE, SWIMMING_DEFINITELY
	};

	enum StrokeState
	{
		STROKE_UNKNOWN, STROKE_BREAST, STROKE_CRAWL, STROKE_BACK, STROKE_BUTTERFLY // NB:
																					// not
																					// sure
																					// how
																					// to
																					// detect
																					// this?
	}

	public class State
	{
		State(SwimState sw, StrokeState st)
		{
			this.swimming = sw;
			this.stroke = st;
		}

		public void reset()
		{
			m_LastHistory = null;
			m_Events.clear();
			stroke = StrokeState.STROKE_UNKNOWN;
			swimming = SwimState.SWIMMING_NOT;
			m_LastRoll = null;
			m_LastThrust = null;
			leftCount = 0;
			rightCount = 0;
			leftOrRightLast=0;
			thrustCount = 0;
			accel_count = 0;
			accel_mean = 0;
			accel_sum2 = 0;
			accel_variance = 0;
			count=0;
		}

		public SwimState swimming;
		public StrokeState stroke;

		public int count;
		public long timeInLength;

		public String debugVals = "";

		private long m_LastTimestamp = 0L;
		// history of state changes
		private ArrayList<EventPoint> m_Events = new ArrayList<EventPoint>(1000);

		private HistoryPoint m_LastHistory = null;
		private EventPoint m_LastRoll = null;
		private EventPoint m_LastThrust = null;

		private OrientationHistoryPoint m_LastOrientation = null;
		private float m_CurrentDirection = 999.999f;
		private long m_LengthStart = 0L;

		// numbers of events (roll left/right, breaststroke kick) that have
		// happened so far this length
		private int leftCount;
		private int rightCount;
		private int thrustCount;
		private int leftOrRightLast=0;

		// statistics on the acceleration, used to identify breaststroke kicks
		private double accel_count;
		private double accel_mean;
		private double accel_sum2;
		private double accel_variance;
	};

	class HistoryPoint
	{
		public long timestamp;

		public HistoryPoint(long timestamp)
		{
			this.timestamp = timestamp;
		}
	};

	class OrientationHistoryPoint extends HistoryPoint
	{
		public float pitch;
		public float roll;
		public float yaw;

		public OrientationHistoryPoint(long timestamp, float pitch, float roll,
				float yaw)
		{
			super(timestamp);
			this.pitch = pitch;
			this.roll = roll;
			this.yaw = yaw;
		}
	}

	class AccelHistoryPoint extends HistoryPoint
	{
		public float x;
		public float y;
		public float z;
		public boolean isLinearAcceleration;

		public AccelHistoryPoint(long timestamp, float x, float y, float z,
				boolean isLinearAcceleration)
		{
			super(timestamp);
			this.x = x;
			this.y = y;
			this.z = z;
			this.isLinearAcceleration = isLinearAcceleration;
		}
	}

	enum EventType
	{
		EVENT_TURN, // turn (yaw change >90 degrees)
		EVENT_ROLL_CHANGE, // roll = 0 flat, -1 left, 1 right
		EVENT_PITCH_CHANGE, // 0 = flat, 1=upright
		EVENT_THRUST, // no value (breaststroke kick)
		EVENT_SWIMSTATE // 0=not swimming, 1= maybe, 2=definitely swimming
	};

	class EventPoint extends HistoryPoint
	{
		public EventType m_Type;
		public int m_Value;

		public EventPoint(long timestamp, EventType type, int value)
		{
			super(timestamp);
			// TODO Auto-generated constructor stub
			m_Type = type;
			m_Value = value;
		}
	}

	private State m_State = new State(SwimState.SWIMMING_NOT,
			StrokeState.STROKE_UNKNOWN);

	private final double SWIM_MAX_ANGLE_FROM_HORIZONTAL = 0.698131701;
	private final double ROLL_STROKE_THRESHOLD = 0.349066;

	public SwimMetricExtractor()
	{

	}

	float angleDifference(float angle1, float angle2)
	{
		float f = (float) (Math.PI - Math.abs(Math.abs(angle1 - angle2)
				- Math.PI));
		return f;
		/*
		 * double diff=Math.abs(angle1-angle2); if(diff>Math.PI) {
		 * diff=(2.0*Math.PI-diff); } return (float) diff;
		 */
	}

	// update the state of the swim tracking
	public void updateState()
	{
		// if no data yet, then can't update state
		if( m_State.m_LastOrientation==null)
		{
			return;
		}

		m_State.m_LastTimestamp=m_State.m_LastHistory.timestamp;
		if(m_State.swimming!=SwimState.SWIMMING_NOT)
		{
			m_State.timeInLength=m_State.m_LastTimestamp-m_State.m_LengthStart;
		}
		
		// detect turns:
		// a turn has happened when the compass direction rotates by >90 degrees:
		// and we have a valid direction value already
		// i.e. this is a turn, rather than a start
		if(m_State.m_CurrentDirection<10.0)
		{
			float directionDiff=angleDifference(m_State.m_LastOrientation.yaw ,m_State.m_CurrentDirection);
			
			if(directionDiff>Math.PI*0.5)
			{
				// we have turned, do something about it
				m_State.reset();
				
				m_State.m_LengthStart=m_State.m_LastTimestamp;
				m_State.m_Events.add(new EventPoint(m_State.m_LastTimestamp,EventType.EVENT_TURN,0));
				Log.e("Turned","trn");
				m_State.m_CurrentDirection=999.0f;
				return;
			}
		}		
		
		// first, work out whether we're swimming or not
		if( m_State.swimming==SwimState.SWIMMING_NOT)
		{
			// if the back angle (pitch) is less than 40 degrees from horizontal, then we might
			// be swimming (the alternative is that it isn't strapped on yet and is still
			// in someone's hands)
			if(Math.abs(m_State.m_LastOrientation.pitch)<SWIM_MAX_ANGLE_FROM_HORIZONTAL)
			{
				m_State.m_Events.add(new EventPoint(m_State.m_LastTimestamp,EventType.EVENT_PITCH_CHANGE,0));
				m_State.swimming=SwimState.SWIMMING_MAYBE;
				m_State.stroke=StrokeState.STROKE_UNKNOWN;
				// mark this as the start of the length if we 
				// haven't just turned (turning sets the lengthstart too)
				if(m_State.m_LastOrientation.timestamp-m_State.m_LengthStart>1000000000L)
				{
					m_State.m_LengthStart=m_State.m_LastOrientation.timestamp;
				}
			}
		}else
		{
			// if we go from horizontal to vertical (in the front to back axis)
			// that means we are standing up (or doing a turn), or at least
			// definitely not swimming
			if(Math.abs(m_State.m_LastOrientation.pitch)>SWIM_MAX_ANGLE_FROM_HORIZONTAL)
			{
				m_State.swimming=SwimState.SWIMMING_NOT;
				m_State.reset();
				m_State.m_Events.add(new EventPoint(m_State.m_LastTimestamp,EventType.EVENT_PITCH_CHANGE,1));
				
				return;
			}
		}
		
		// detect roll events (flat vs on side vs upside down)
		if(m_State.swimming!=SwimState.SWIMMING_NOT && m_State.timeInLength>1000000000L)
		{
			int rollState=-5;

			OrientationHistoryPoint ori=m_State.m_LastOrientation;
			if(Math.abs(ori.roll)>Math.PI*0.5)
			{
				// upside down roll events
				if(Math.abs(ori.roll)>Math.PI-ROLL_STROKE_THRESHOLD)
				{
					// upside down flat
					rollState=10;
				}else if(ori.roll<0)
				{
					// upside down left
					rollState=11;
				}else
				{
					// upside down right
					rollState=9;
				}
			}else if(ori.roll>ROLL_STROKE_THRESHOLD)
			{
				// 20 degrees one way
				rollState=1;
			}else if(ori.roll<-ROLL_STROKE_THRESHOLD)
			{
				// 20 degrees the other way
				rollState=-1;
			}else
			{
				rollState=0;
			}
			if(m_State.m_LastRoll==null || m_State.m_LastRoll.m_Value!=rollState)
			{
				m_State.m_LastRoll=new EventPoint(m_State.m_LastTimestamp,EventType.EVENT_ROLL_CHANGE,rollState);
				m_State.m_Events.add(m_State.m_LastRoll);
				if( rollState==-1 || rollState==9)
				{
					if(m_State.leftOrRightLast!=-1)
					{
						m_State.leftCount+=1;
						m_State.leftOrRightLast=-1;
					}
				}else if( rollState==1 || rollState==11)
				{
					if(m_State.leftOrRightLast!=1)
					{
						m_State.rightCount+=1;
						m_State.leftOrRightLast=1;
					}
				}
			}
		}

		
		
		
		// detect thrust events (breaststroke kicks), only if we have not detected that we're swimming crawl already
		if(m_State.swimming!=SwimState.SWIMMING_NOT && m_State.timeInLength>1000000000L)
		{
			if(m_State.m_LastHistory instanceof AccelHistoryPoint)
			{	
				double value=0.0;
				AccelHistoryPoint pt = ((AccelHistoryPoint)m_State.m_LastHistory);
				if(pt.isLinearAcceleration)
				{
					value=-pt.y;
				}else
				{
					value=Math.sqrt(pt.x*pt.x+pt.y*pt.y+pt.z*pt.z)-SensorManager.GRAVITY_EARTH;
				}
				m_State.debugVals=""+value;
				m_State.accel_count+=1.0;
				double delta=value-m_State.accel_mean;
				m_State.accel_mean+=delta/m_State.accel_count;
				m_State.accel_sum2+=delta*(value-m_State.accel_mean);
				
				boolean tooClose=true;
				if(m_State.m_LastThrust==null || m_State.m_LastTimestamp-m_State.m_LastThrust.timestamp>500000000L)
				{
					tooClose=false;
				}
				
				m_State.accel_variance=m_State.accel_sum2 / (m_State.accel_count-1.0);
				
				
				if(m_State.accel_count>50.0 && tooClose==false)
				{
					// 2 standard deviations above mean = a thrust (and a minimum of 1m/s/s)
					double valueOffset=value-m_State.accel_mean;
					if(valueOffset*valueOffset>m_State.accel_variance*4.0 && (!pt.isLinearAcceleration || value>1.0))
					{
						Log.e("acc","acc:"+value+" count:"+m_State.accel_count+" mean:"+m_State.accel_mean+" var:"+m_State.accel_variance);
						// detected a thrust
						m_State.thrustCount+=1;
						m_State.m_LastThrust=new EventPoint(m_State.m_LastTimestamp,EventType.EVENT_THRUST,0);
						m_State.m_Events.add(m_State.m_LastThrust);
					}			
				}
			}
		}
		
		// detect stroke - if we've already detected lots of roll, then rule out breaststroke
		// but if we detect breaststroke then let it switch to crawl	
		if(m_State.swimming!=SwimState.SWIMMING_NOT)
		{
			EventPoint lastRoll=m_State.m_LastRoll;
			if(m_State.stroke!=StrokeState.STROKE_CRAWL && m_State.stroke!=StrokeState.STROKE_BACK)
			{
				// look for thrust events
				// or left/right events
				if(m_State.leftCount+m_State.rightCount>=3)
				{
					m_State.stroke=StrokeState.STROKE_CRAWL;
					if(lastRoll!=null)
					{
						// just choose front or back crawl
						if(lastRoll.m_Value>1)
						{
							m_State.stroke=StrokeState.STROKE_BACK;
						}					
					}
				}else if(m_State.thrustCount>1)
				{
					m_State.stroke=StrokeState.STROKE_BREAST;
				}
			}else
			{
				// some kind of crawl, just use upsidedownness to detect it
				if(lastRoll!=null)
				{
					// just choose front or back crawl
					if(lastRoll.m_Value>1)
					{
						m_State.stroke=StrokeState.STROKE_BACK;
					}else
					{
						m_State.stroke=StrokeState.STROKE_CRAWL;
					}					
				}
			}
		}
		
		// if we know the stroke, then count strokes since this length started
		if( m_State.swimming != SwimState.SWIMMING_NOT && m_State.stroke!=StrokeState.STROKE_UNKNOWN)			
		{
			int strokes=0;
			switch(m_State.stroke)
			{
			case STROKE_BACK:
			case STROKE_CRAWL:
				// use the roll, and count a stroke each time they
				// roll >20 degrees to either side
				strokes=m_State.leftCount+m_State.rightCount;
				break;
			case STROKE_BREAST:
			case STROKE_BUTTERFLY:
				strokes=m_State.thrustCount;
				break;
			case STROKE_UNKNOWN:
			default:
				// SHOULD NEVER GET HERE
				break;
			}
			m_State.count=strokes;			
		}			
	}


	public void onOrientationChange(long timestamp, float yaw, float pitch,
			float roll)
	{
		OrientationHistoryPoint op = new OrientationHistoryPoint(timestamp,
				pitch, roll, yaw);
		m_State.m_LastHistory = op;
		m_State.m_LastOrientation = op;
		m_State.m_LastTimestamp = timestamp;
		updateState();
	}

	public void onLinearAcceleration(long timestamp, float x, float y, float z)
	{
		m_State.m_LastTimestamp = timestamp;
		m_State.m_LastHistory = new AccelHistoryPoint(timestamp, x, y, z, true);
		updateState();
	}

	public State getState()
	{
		return m_State;
	}

	public void onGlobalAcceleration(long timestamp, float x, float y, float z)
	{
		m_State.m_LastTimestamp = timestamp;
		m_State.m_LastHistory = new AccelHistoryPoint(timestamp, x, y, z, false);
		updateState();
	}

}
