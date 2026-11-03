package com.example.cinemacamera;

/** Main-thread sensor queue. Complete at acquisition; processing has a separate budget. */
public final class CaptureQueue {
    private final int capacity;
    private int queued;
    private boolean holding,inFlight;
    public CaptureQueue(int capacity) {
        if(capacity<1) throw new IllegalArgumentException("Positive capacity required");
        this.capacity=capacity;
    }
    public boolean tap() {
        if(queued>=capacity) return false;
        queued++; return true;
    }
    public void startHold() { holding=true; }
    public void stopHold() { holding=false; }
    public boolean isHolding() { return holding; }
    public boolean isInFlight() { return inFlight; }
    public int queuedCount() { return queued; }
    public boolean startNext() {
        if(inFlight||(!holding&&queued==0)) return false;
        if(queued>0) queued--;
        inFlight=true; return true;
    }
    public void complete(boolean success) {
        inFlight=false;
        if(!success) clear();
    }
    public void clear() { queued=0; holding=false; inFlight=false; }
}
