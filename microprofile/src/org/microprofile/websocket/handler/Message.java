package org.microprofile.websocket.handler;

public class Message {
	public static final byte OPCODE_PART = 0X0;
	public static final byte OPCODE_PART_STRING = 0X1;
	public static final byte OPCODE_PART_BYTE = 0X2;
	public static final byte OPCODE_CLOSE = 0X8;
	public static final byte OPCODE_PING = 0X9;
	public static final byte OPCODE_PONG = 0XA;
	private final boolean fin;
	private final int rsv;
	private final byte opCode;
	private final byte[] payload;

	public Message(boolean fin, int rsv, byte opCode, byte[] payload) {
		this.fin = fin;
		this.rsv = rsv;
		this.opCode = opCode;
		this.payload = payload;
	}

	public boolean isFin() {
		return this.fin;
	}

	public int getRsv() {
		return this.rsv;
	}

	public byte getOpCode() {
		return this.opCode;
	}

	public byte[] getPayload() {
		return this.payload;
	}

}
