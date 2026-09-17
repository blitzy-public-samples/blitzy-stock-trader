package com.ibm.hybrid.cloud.sample.portfolio.pojo;

public class LoopResult {
    private long loopDuration = -1;
    private boolean successfulRun = false;
    private String result;

    public LoopResult(long loopDuration, boolean successfulRun, String result) {
        this.loopDuration = loopDuration;
        this.successfulRun = successfulRun;
        this.result = result;
    }

    public boolean isSuccessfulRun() {
        return successfulRun;
    }

    public void setSuccessfulRun(boolean successfulRun) {
        this.successfulRun = successfulRun;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public long getLoopDuration() {
        return loopDuration;
    }

    public void setLoopDuration(long loopDuration) {
        this.loopDuration = loopDuration;
    }

    @Override
    public String toString() {
        return "LoopResult{" +
                "loopDuration=" + loopDuration +
                ", successfulRun=" + successfulRun +
                ", result='" + result + '\'' +
                '}';
    }
}
