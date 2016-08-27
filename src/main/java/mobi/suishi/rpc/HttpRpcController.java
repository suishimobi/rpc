package mobi.suishi.rpc;

import java.util.HashMap;
import java.util.Map;

import mobi.suishi.rpc.HttpRpcProtos.ErrorReason;

import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;

public class HttpRpcController implements RpcController {
	
	public static int RPC_FLAG_ENCRYPT = 0x01;

	private boolean failed = false;
	private String error = null;
	private ErrorReason reason = null;
	private int flag = 0;
	private String sign = null;
	private int clientVersion = 0;
	private String packageName = null;
	private String channel = null;
	
	private Map<String, Object> parameters = new HashMap<String, Object>();
	
	
	
	public String getPackageName() {
		return packageName;
	}


	public void setPackageName(String packageName) {
		this.packageName = packageName;
	}


	public void putParameter(String name, Object value) {
		parameters.put(name, value);
	}
	
	
	public Object getParameter(String name) {
		return parameters.get(name);
	}
	
	
	public int getClientVersion() {
		return clientVersion;
	}

	public void setClientVersion(int clientVersion) {
		this.clientVersion = clientVersion;
	}
	

	public String getChannel() {
		return channel;
	}


	public void setChannel(String channel) {
		this.channel = channel;
	}


	public int getFlag() {
		return flag;
	}

	public void setFlag(int flag) {
		this.flag = flag;
	}

	public String getSign() {
		return sign;
	}

	public void setSign(String sign) {
		this.sign = sign;
	}

 	@Override
	public String errorText() {
		return error;
	}

	@Override
	public boolean failed() {
		return failed;
	}

	@Override
	public boolean isCanceled() {
		// Not yet supported
		throw new UnsupportedOperationException(
				"Cannot cancel request in Http RPC");
	}

	@Override
	public void notifyOnCancel(RpcCallback<Object> arg0) {
		// Not yet supported
		throw new UnsupportedOperationException(
				"Cannot cancel request in Http RPC");
	}

	public ErrorReason errorReason() {
		return reason;
	}

	@Override
	public void reset() {
		failed = false;
		error = null;
		reason = null;
	}

	@Override
	public void setFailed(String arg0) {
		failed = true;
		error = arg0;
	}

	public void setFailed(String error, ErrorReason errorReason) {
		setFailed(error);
		reason = errorReason;
	}

	@Override
	public void startCancel() {
		// Not yet supported
		throw new UnsupportedOperationException(
				"Cannot cancel request in Http RPC");
	}

}
