package com.joemarshall.swimcounter;

import java.util.ArrayList;

public class SwimMetricExtractor
{
	interface Callback
	{
		// called on a stroke or whatever (see EventPoint)
		public void onEvent(EventPoint event);
		// called when a length is complete
		public void onLengthComplete(LengthStatistics stats);
		// called to log something as an error
		public void logError(String tag,String value);
		// called to log something as an info
		public void logInfo(String tag,String value);
	}
	
	static final double GRAVITY_EARTH=9.80665;
	
	enum SwimState
	{
		SWIMMING_NOT, SWIMMING_MAYBE, SWIMMING_DEFINITELY
	};

	enum StrokeState
	{
		STROKE_UNKNOWN("Unknown"), STROKE_BREAST("Breast"), STROKE_CRAWL("Crawl"), STROKE_BACK("Back"), STROKE_BUTTERFLY("Fly"); 
		
		private String m_Text;

		// NB:
		// not
		// sure
		// how
		// to
		// detect
		// butterfly vs breaststroke

		private StrokeState(String text)
		{
			this.m_Text=text;
		}
		
		public String toString()
		{
			return m_Text;
		}
	}

	enum EndType
	{
		END_TURNING, // length has finished by doing a turn 
		END_TURNED, // new length has started straight after a turn was detected
		END_NOT_TURNED // new length has started, but not straight after a turn
	};
	
	enum TurnType
	{
		TURN_STOP("Stopped"),
		TURN_FLIP("Flip"),
		TURN_OPEN("Open turn");
		
		private String m_Text;
		TurnType(String text)
		{
			m_Text = text;
		}
		
		public String toString()
		{
			return m_Text;
		}
	}
	
	
	public class State
	{
		State()
		{
			taps=0;
			reset();
		}

		public void reset()
		{
			lastHistory = null;
			events.clear();
			stroke = StrokeState.STROKE_UNKNOWN;
			swimming = SwimState.SWIMMING_NOT;
			lastRoll = null;
			lastThrust = null;
			leftCount = 0;
			rightCount = 0;
			leftOrRightLast = 0;
			thrustCount = 0;
			pd.clear();
			count = 0;
			direction_meanX = 0.;
			direction_meanY = 0.;
			direction_count = 0.;
			timeInLength=0L;
		}
		
		public void addEvent(EventPoint event)
		{
			events.add(event);
			m_Callback.onEvent(event);
		}

		public SwimState swimming;
		public StrokeState stroke;

		public int count;
		public long timeInLength;

		public String debugVals = "";

		public long lastTimestamp = 0L;
		// history of state changes
		private ArrayList<EventPoint> events = new ArrayList<EventPoint>(1000);

		private HistoryPoint lastHistory = null;
		private EventPoint lastRoll = null;
		private EventPoint lastThrust = null;

		private OrientationHistoryPoint lastOrientation = null;
		private double currentDirection = 999.999;
		private long lengthStart = 0L;

		// numbers of stroke events (roll left/right, breaststroke kick) that have
		// happened so far this length
		private int leftCount;
		private int rightCount;
		private int thrustCount;
		private int leftOrRightLast = 0;

		// statistics on the acceleration, used to identify breaststroke kicks
		public EventPoint lastPitchChange;

		// statistics on the compass direction, we use the vector mean of this
		// to estimate the pool direction
		private double direction_meanX;
		private double direction_meanY;
		private double direction_count;
		
		// detects breaststroke
		SinglePeakDetector pd=new SinglePeakDetector();
		TapDetector td=new TapDetector();
		public int taps;
		public boolean isTumbleTurn;
	};

	
	
	class HistoryPoint
	{
		public long timestamp;

		public HistoryPoint()
		{
		}
		
		public void init(long timestamp)
		{
			this.timestamp = timestamp;			
		}
	};

	class OrientationHistoryPoint extends HistoryPoint
	{
		public float pitch;
		public float roll;
		public float yaw;

		public OrientationHistoryPoint()
		{
		}
		
		public void init(long timestamp, float pitch, float roll,
				float yaw)
		{
			super.init(timestamp);
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

		public AccelHistoryPoint()
		{		
		}
		
		public void init(long timestamp, float x, float y, float z,
				boolean isLinearAcceleration)
		{
			super.init(timestamp);
			this.x = x;
			this.y = y;
			this.z = z;
			this.isLinearAcceleration = isLinearAcceleration;
		}
	}

	enum EventType
	{
		EVENT_START,// start of length from standing (value=0), or turn (value=1)
		EVENT_ROLL_CHANGE, // roll = 0 flat, -1 left, 1 right
		EVENT_PITCH_CHANGE, // 0 = flat, 1=upright
		EVENT_THRUST, // no value (breaststroke kick)
		EVENT_SWIMSTATE // 0=not swimming, 1= maybe, 2=definitely swimming
	};

	class EventPoint 
	{
		public EventType m_Type;
		public int m_Value;
		public long timestamp;

		public EventPoint(long timestamp, EventType type, int value)
		{
			this.timestamp=timestamp;
			// TODO Auto-generated constructor stub
			m_Type = type;
			m_Value = value;
		}
	}

	private State m_State = new State();

	private final double SWIM_MAX_ANGLE_FROM_HORIZONTAL = 0.698131701;
	private final double ROLL_STROKE_THRESHOLD = 0.349066;

	private Callback m_Callback;

	public SwimMetricExtractor(Callback cb)
	{
		m_Callback=cb;
	}

	double angleDifference(double angle1, double angle2)
	{
		// double f = (Math.PI - Math.abs(Math.abs(angle1 - angle2)
		// - Math.PI));
		// return f;

		double diff = Math.abs(angle1 - angle2);
		if (diff > Math.PI)
		{
			diff = (2.0 * Math.PI - diff);
		}
		return (float) diff;

	}

	// update the state of the swim tracking
	public void updateState()
	{
		// if no data yet, then can't update state
		if (m_State.lastOrientation == null)
		{
			return;
		}
		// m_State.debugVals=""+m_State.m_LastOrientation.yaw;

		m_State.lastTimestamp = m_State.lastHistory.timestamp;
		if (m_State.swimming != SwimState.SWIMMING_NOT)
		{
			m_State.timeInLength = m_State.lastTimestamp
					- m_State.lengthStart;
		}

		if(m_State.lastHistory instanceof AccelHistoryPoint)
		{
			AccelHistoryPoint at=(AccelHistoryPoint) m_State.lastHistory ;
			m_State.td.addValue(at.timestamp, at.z);
			m_State.taps=m_State.td.numPeaks();
			
			m_State.debugVals=""+m_State.taps+":\n"+at.z;
		}else
		{
//			m_Callback.logInfo("pitch",""+m_State.lastOrientation.pitch);
		}
		
		
		// detect turns:
		// a turn has happened when the compass direction rotates by >90
		// degrees:
		// and we have a valid direction value already
		// i.e. this is a turn, rather than a start
		if (m_State.currentDirection < 10.0)
		{
			double directionDiff = angleDifference(
					m_State.lastOrientation.yaw, m_State.currentDirection);
//			m_Callback.logInfo("dir", String.format("%02.2f : %02.2f : %02.2f",m_State.currentDirection,m_State.lastOrientation.yaw,directionDiff));
			if (directionDiff > Math.PI * 0.5)
			{
				// we have turned, so this must be a new length
				if(m_State.events.size()>0)
				{
					onEndOfLength(EndType.END_TURNING);

					m_State.lengthStart = m_State.lastTimestamp;
					m_State.addEvent(new EventPoint(m_State.lastTimestamp,
							EventType.EVENT_START, 1));
//					m_Callback.logError("dir", String.format("trn: %02.2f : %02.2f : %02.2f",m_State.currentDirection,m_State.lastOrientation.yaw,directionDiff));
					
				}
				m_State.currentDirection = 999.0;
				m_State.swimming = SwimState.SWIMMING_NOT;
				m_State.direction_count = 0;
				m_State.direction_meanX = 0;
				m_State.direction_meanY = 0;
				
				return;
			}
		}

		// first, work out whether we're swimming or not
		if (m_State.swimming == SwimState.SWIMMING_NOT)
		{
			// if the back angle (pitch) is less than 40 degrees from
			// horizontal, then we might
			// be swimming (the alternative is that it isn't strapped on yet and
			// is still
			// in someone's hands)
			if (Math.abs(m_State.lastOrientation.pitch) < SWIM_MAX_ANGLE_FROM_HORIZONTAL)
			{
				// mark this as the start of the length if we
				// haven't just turned (turning sets the lengthstart too)
				
				if(m_State.lastPitchChange != null && m_State.lastTimestamp
								- m_State.lastPitchChange.timestamp <= 1000000000L && m_State.lastPitchChange.timestamp>m_State.lengthStart)
				{
					// if we just popped up very quickly it might just be a sensor error
					// caused by accelerations, ignore
				}else if(m_State.lastOrientation.timestamp - m_State.lengthStart < 2000000000L)
				{
					// after a turn, we did the turn
					// need to finalise the turn length
					onEndOfLength(EndType.END_TURNED);
				}else
				{
					// this was not a turn, finalize the previous length
					onEndOfLength(EndType.END_NOT_TURNED);
					m_State.lengthStart = m_State.lastOrientation.timestamp;
					m_State.addEvent(new EventPoint(m_State.lastTimestamp,
							EventType.EVENT_START, 0));
				}
				m_State.lastPitchChange = new EventPoint(
						m_State.lastTimestamp, EventType.EVENT_PITCH_CHANGE,
						0);
				m_State.addEvent(m_State.lastPitchChange);
				m_State.swimming = SwimState.SWIMMING_MAYBE;
			} else
			{
				if (m_State.lastPitchChange != null)
				{
					if(m_State.events.size()>0)
					{
						long timeDiff = m_State.lastTimestamp
								- m_State.lastPitchChange.timestamp;
						if (timeDiff > 5000000000L)
						{
							// hanging around for 5 seconds standing up = end of
							// length for sure
							onEndOfLength(EndType.END_NOT_TURNED);
						}
					}
				}
			}
		} else
		{
			// if we go from horizontal to vertical (in the front to back axis)
			// that means we are standing up (or doing a turn), or at least
			// definitely not swimming
			if (Math.abs(m_State.lastOrientation.pitch) > SWIM_MAX_ANGLE_FROM_HORIZONTAL)
			{
				m_State.swimming = SwimState.SWIMMING_NOT;
				m_State.lastPitchChange = new EventPoint(
						m_State.lastTimestamp, EventType.EVENT_PITCH_CHANGE,
						1);
				if(m_State.lastOrientation.pitch > SWIM_MAX_ANGLE_FROM_HORIZONTAL)
				{
					// pointing downwards, i.e. doing a tumble turn
					m_State.isTumbleTurn=true;
				}else
				{
					m_State.isTumbleTurn=false;
				}
				m_State.addEvent(m_State.lastPitchChange);
				return;
			}
			
		}

		// detect roll events (flat vs on side vs upside down)
		if (m_State.swimming != SwimState.SWIMMING_NOT
				&& m_State.timeInLength > 1000000000L)
		{
			int rollState = -5;

			OrientationHistoryPoint ori = m_State.lastOrientation;
			if (Math.abs(ori.roll) > Math.PI * 0.5)
			{
				// upside down roll events
				if (Math.abs(ori.roll) > Math.PI - ROLL_STROKE_THRESHOLD)
				{
					// upside down flat
					rollState = 10;
				} else if (ori.roll < 0)
				{
					// upside down left
					rollState = 11;
				} else
				{
					// upside down right
					rollState = 9;
				}
			} else if (ori.roll > ROLL_STROKE_THRESHOLD)
			{
				// 20 degrees one way
				rollState = 1;
			} else if (ori.roll < -ROLL_STROKE_THRESHOLD)
			{
				// 20 degrees the other way
				rollState = -1;
			} else
			{
				rollState = 0;
			}
			if (m_State.lastRoll == null
					|| m_State.lastRoll.m_Value != rollState)
			{
				m_State.lastRoll = new EventPoint(m_State.lastTimestamp,
						EventType.EVENT_ROLL_CHANGE, rollState);
				m_State.addEvent(m_State.lastRoll);
				switch(rollState)
				{
				case -1:
					if(m_State.leftOrRightLast==1)
					{
						m_State.leftCount+=1;
					}else if(m_State.leftOrRightLast!=-1)
					{
						// either from upside down or first stroke
						// so set left count to 1
						m_State.leftCount=1;
					}
					break;
				case 9:
					if(m_State.leftOrRightLast==11)
					{
						m_State.leftCount+=1;
					}else if(m_State.leftOrRightLast!=9)
					{
						// either from upside down or first stroke
						// so set left count to 1
						m_State.leftCount=1;
					}
					break;
				case 1:
					if(m_State.leftOrRightLast==-1)
					{
						m_State.rightCount+=1;
					}else if(m_State.leftOrRightLast!=1)
					{
						// either from upside down or first stroke
						// so set right count to 1
						m_State.rightCount=1;
					}
					break;
				case 11:
					if(m_State.leftOrRightLast==9)
					{
						m_State.rightCount+=1;
					}else if(m_State.leftOrRightLast!=11)
					{
						// either from upside down or first stroke
						// so set right count to 1
						m_State.rightCount=1;
					}
					break;
				}
				if(rollState!=0 && rollState!=10)
				{
					m_State.leftOrRightLast =rollState;
				}
			}
		}

		if (m_State.swimming != SwimState.SWIMMING_NOT
				&& m_State.timeInLength > 1000000000L
				&& m_State.lastHistory instanceof OrientationHistoryPoint)
		{
			m_State.direction_count += 1.0;
			m_State.direction_meanX += Math.cos(m_State.lastOrientation.yaw);
			m_State.direction_meanY += Math.sin(m_State.lastOrientation.yaw);
			if (m_State.direction_count > 100.0)
			{
				m_State.currentDirection = Math.atan2(
						m_State.direction_meanY, m_State.direction_meanX);
//				m_State.debugVals = "" + m_State.currentDirection;
			}
		}

		// detect thrust events (breaststroke kicks), only if we have not
		// detected that we're swimming crawl already
		if (m_State.swimming != SwimState.SWIMMING_NOT
				&& m_State.timeInLength > 1000000000L)
		{
			if (m_State.lastHistory instanceof AccelHistoryPoint)
			{
				double value = 0.0;
				AccelHistoryPoint pt = ((AccelHistoryPoint) m_State.lastHistory);
				if (pt.isLinearAcceleration)
				{
					value = pt.y;
				} else
				{
					value = Math.sqrt(pt.x * pt.x + pt.y * pt.y + pt.z * pt.z)
							- GRAVITY_EARTH;
				}
				
				boolean tooClose = true;
				if (m_State.lastThrust == null
						|| m_State.lastTimestamp
								- m_State.lastThrust.timestamp > 500000000L)
				{
					tooClose = false;
				}
				
				m_State.pd.addValue(pt.timestamp, (float)value);
				if (tooClose == false && m_State.pd.isPeak() )
				{
					// detected a thrust
					m_State.thrustCount += 1;
					m_State.lastThrust = new EventPoint(
							m_State.lastTimestamp,
							EventType.EVENT_THRUST, 0);
					m_State.addEvent(m_State.lastThrust);
					
				}
			}
		}

		// detect stroke - if we've already detected lots of roll, then rule out
		// breaststroke
		// but if we detect breaststroke then let it switch to crawl
		// because we could has misclassified a push off as a breaststroke kick or something
		if (m_State.swimming != SwimState.SWIMMING_NOT)
		{
			EventPoint lastRoll = m_State.lastRoll;
			if (m_State.stroke != StrokeState.STROKE_CRAWL
					&& m_State.stroke != StrokeState.STROKE_BACK)
			{
				// look for thrust events
				// or left/right events
				if (m_State.leftCount + m_State.rightCount >= 3)
				{
					m_State.stroke = StrokeState.STROKE_CRAWL;
					if (lastRoll != null)
					{
						// just choose front or back crawl
						if (lastRoll.m_Value > 1)
						{
							m_State.stroke = StrokeState.STROKE_BACK;
						}
					}
				} else if (m_State.thrustCount > 1)
				{
					m_State.stroke = StrokeState.STROKE_BREAST;
				}
			} else
			{
				// some kind of crawl, just use upsidedownness to detect it
				if (lastRoll != null)
				{
					// just choose front or back crawl
					if (lastRoll.m_Value > 1)
					{
						m_State.stroke = StrokeState.STROKE_BACK;
					} else
					{
						m_State.stroke = StrokeState.STROKE_CRAWL;
					}
				}
			}
		}

		// if we know the stroke, then count strokes since this length started
		if (m_State.swimming != SwimState.SWIMMING_NOT
				&& m_State.stroke != StrokeState.STROKE_UNKNOWN)
		{
			int strokes = 0;
			switch (m_State.stroke)
			{
			case STROKE_BACK:
			case STROKE_CRAWL:
				// use the roll, and count a stroke each time they
				// roll >20 degrees to either side
				strokes = m_State.leftCount + m_State.rightCount;
				break;
			case STROKE_BREAST:
			case STROKE_BUTTERFLY:
				strokes = m_State.thrustCount;
				break;
			case STROKE_UNKNOWN:
			default:
				// SHOULD NEVER GET HERE
				break;
			}
			m_State.count = strokes;
		}
	}

	
	class LengthStatistics
	{
		public long lengthTime=0;		
		public int strokes=0;
		public StrokeState stroke;
		public long lengthStart=0;
		public double direction=0;
		public TurnType turnType=TurnType.TURN_STOP;

		public LengthStatistics(State state)
		{
			strokes=state.count;
			lengthTime=state.timeInLength;
			lengthStart=state.lengthStart;
			stroke=state.stroke;	
			direction=state.currentDirection;
		}
		
		public void write()
		{
			if(	lengthTime>4000000000L &&lengthTime<300000000000L)
			{
				m_Callback.onLengthComplete(this);
//				Log.v("length",String.format("%f,%d,%s,%b",((double)lengthTime)/1000000000.0,strokes,stroke.toString(),turned));
			}
		}		
	}
	
	LengthStatistics m_PreviousLength=null;
	LengthStatistics m_FinishedLength=null;
	// called when we think an end of length has occurred
	// Note: when we detect a turn, we don't write it until
	// either a)start of next length has occurred
	//          - write it, with the time of end of length being the time of turn
	//            (when the compass direction reached 90 degrees from swim direction)
	//            
	// or     b)2 seconds have elapsed
	//          - write it, with the time of end of length being the time
	//            that the person went from horizontal to vertical
	//          - this assumes that if >2 seconds happen after a turn,
	//            the person stood up and turned round, rather than swimming off
	//			
	private void onEndOfLength(EndType detectedTurn)
	{
		// log a length, if:
		// 1)last length direction is different
		// 2)time is >8 seconds
		// 3)time <5 minutes
		//Log.e("length","onEnd:"+detectedTurn.toString()+m_State.events.size());
		if(m_State.events.size()==0)
		{
			// if there are no events, then we must have finished the previous length, but 
			// not started the next one (or done a turn, as that adds a turn event in)
			return;
		}
		switch(detectedTurn)
		{
		case END_NOT_TURNED:
			if(m_FinishedLength!=null && m_State.events.size()>0)
			{		
//				Log.e("length","not turned after turn");
				// if there was a possible turn, then write it
				// but as a finished lap not as a turn
				m_FinishedLength.turnType=TurnType.TURN_STOP;
				m_FinishedLength.write();
				m_PreviousLength=m_FinishedLength;
				m_FinishedLength=null;
			}else
			{				
//				Log.e("length","not turned");
				// no possible turn, just write out the last length
				// from whenever it finished
				m_PreviousLength=new LengthStatistics(m_State);
				m_PreviousLength.turnType=TurnType.TURN_STOP;
				m_PreviousLength.write();
			}
			break;
		case END_TURNED:
			if(m_FinishedLength!=null && m_State.events.size()>0)
			{
				if( m_State.isTumbleTurn)
				{
					m_FinishedLength.turnType=TurnType.TURN_FLIP;
				}
				long lengthEndTime=m_State.events.get(0).timestamp;
				m_FinishedLength.lengthTime=lengthEndTime-m_FinishedLength.lengthStart;
				m_FinishedLength.write();
				m_PreviousLength=m_FinishedLength;
				m_FinishedLength=null;
				
//				Log.e("length","good turn");
			}else
			{
				// only gets here if the turn happens straight after the start of a 
				// length
//				Log.e("length","bad turn");
			}
			break;
		case END_TURNING:
			// don't write this length until we know whether it was a turn or
			// the person standing up
//			Log.e("length","turning");
			m_FinishedLength=new LengthStatistics(m_State);
			if( m_State.isTumbleTurn)
			{
				m_Callback.logError("trn", "tumb");
				
				m_FinishedLength.turnType=TurnType.TURN_FLIP;
			}else
			{
				m_Callback.logError("trn", "not");
				m_FinishedLength.turnType=TurnType.TURN_OPEN;
			}
			break;
		
		}
		m_State.reset();
	}

	
	OrientationHistoryPoint oriPoint=new OrientationHistoryPoint();
	public void onOrientationChange(long timestamp, float yaw, float pitch,
			float roll)
	{
		oriPoint.init(timestamp,
				pitch, roll, yaw);
		m_State.lastHistory = oriPoint;
		m_State.lastOrientation = oriPoint;
		m_State.lastTimestamp = timestamp;

		updateState();
	}

	AccelHistoryPoint accPoint=new AccelHistoryPoint();
	public void onLinearAcceleration(long timestamp, float x, float y, float z)
	{
		m_State.lastTimestamp = timestamp;
		accPoint.init(timestamp, x, y, z, true);
		m_State.lastHistory = accPoint;
		updateState();
	}

	public State getState()
	{
		return m_State;
	}
	
	public void onGlobalAcceleration(long timestamp, float x, float y, float z)
	{
		m_State.lastTimestamp = timestamp;
		accPoint.init(timestamp, x, y, z, false);
		m_State.lastHistory = accPoint;
		updateState();
	}

	
}

