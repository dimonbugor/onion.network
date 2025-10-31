package onion.network.call;

public final class CallSession {
    public final String remoteAddress;
    public final boolean outgoing;
    public final String callId;
    public final long startedAt;

    CallSession(String remoteAddress, boolean outgoing, String callId) {
        this.remoteAddress = remoteAddress;
        this.outgoing = outgoing;
        this.callId = callId;
        this.startedAt = System.currentTimeMillis();
    }
}
