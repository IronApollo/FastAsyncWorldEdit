package com.boydti.fawe.object.io;

import java.util.concurrent.Callable;

public class PGZIPBlock implements Callable<byte[]> {
    public PGZIPBlock(final PGZIPOutputStream parent) {
        state = new PGZIPThreadLocal(parent);
    }

    /**
     * This ThreadLocal avoids the recycling of a lot of memory, causing lumpy performance.
     */
    protected final ThreadLocal<PGZIPState> state;
    public static final int SIZE = 64 * 1024;
    // private final int index;
    protected final byte[] in = new byte[SIZE];
    protected int in_length = 0;

    /*
     public Block(@Nonnegative int index) {
     this.index = index;
     }
     */
    // Only on worker thread
    @Override
    public byte[] call() throws Exception {
        // LOG.info("Processing " + this + " on " + Thread.currentThread());

        PGZIPState state = this.state.get();
        // ByteArrayOutputStream buf = new ByteArrayOutputStream(in.length);   // Overestimate output size required.
        // DeflaterOutputStream def = newDeflaterOutputStream(buf);
        state.def.reset();
        state.buf.reset();
        state.str.write(in, 0, in_length);
        state.str.flush();

        // return Arrays.copyOf(in, in_length);
        return state.buf.toByteArray();
    }

    @Override
    public String toString() {
        return "Block" /* + index */ + "(" + in_length + "/" + in.length + " bytes)";
    }
}
