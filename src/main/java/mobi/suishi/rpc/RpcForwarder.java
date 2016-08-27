package mobi.suishi.rpc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import mobi.suishi.rpc.HttpRpcProtos.ErrorReason;
import mobi.suishi.rpc.HttpRpcProtos.Request;
import mobi.suishi.rpc.HttpRpcProtos.Response;
import mobi.suishi.rpc.HttpRpcProtos.Response.Builder;
import mobi.suishi.security.AESCrypto;

import com.google.protobuf.BlockingService;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.Service;
import com.google.protobuf.ServiceException;

/*
 * changed based on 
 * https://github.com/sdeo/protobuf-socket-rpc/blob/master/java/src/main/java/com/googlecode/protobuf/socketrpc/RpcForwarder.java
 */
public class RpcForwarder {

	private final Map<String, Service> serviceMap = new HashMap<String, Service>();
	private final Map<String, BlockingService> blockingServiceMap = new HashMap<String, BlockingService>();

	/**
	 * Register an RPC service implementation to this forwarder.
	 */
	public void registerService(Service service) {
		serviceMap.put(service.getDescriptorForType().getFullName(), service);
	}

	/**
	 * Register an RPC blocking service implementation to this forwarder.
	 */
	public void registerBlockingService(BlockingService service) {
		blockingServiceMap.put(service.getDescriptorForType().getFullName(),
				service);
	}

	/**
	 * Handle the blocking RPC request by forwarding it to the correct
	 * service/method.
	 * 
	 * @throws RpcException
	 *             If there was some error executing the RPC.
	 */
	public HttpRpcProtos.Response doBlockingRpc(HttpRpcProtos.Request rpcRequest,
			HttpRpcController httpRpcController)
			throws RpcException {
		// Get the service, first try BlockingService
		BlockingService blockingService = blockingServiceMap.get(rpcRequest
				.getServiceName());
		
		if (blockingService != null) {
			return forwardToBlockingService(rpcRequest, blockingService, httpRpcController);
		}

		// Now try Service
		Service service = serviceMap.get(rpcRequest.getServiceName());
		if (service == null) {
			throw new RpcException(ErrorReason.SERVICE_NOT_FOUND,
					"Could not find service: " + rpcRequest.getServiceName(),
					null);
		}

		// Call service using an instant callback
		Callback<Message> callback = new Callback<Message>();
		forwardToService(rpcRequest, callback, service, httpRpcController);

		// Build and return response (callback invocation is optional)
		return createRpcResponse(callback.response, callback.invoked,
				httpRpcController);
	}

	/**
	 * Handle the the non-blocking RPC request by forwarding it to the correct
	 * service/method.
	 * 
	 * @throws RpcException
	 *             If there was some error executing the RPC.
	 */
	public void doRpc(HttpRpcProtos.Request rpcRequest,
			final RpcCallback<HttpRpcProtos.Response> rpcCallback,
			final HttpRpcController httpRpcController)
			throws RpcException {

		// Get the service, first try BlockingService
		BlockingService blockingService = blockingServiceMap.get(rpcRequest
				.getServiceName());
		if (blockingService != null) {
			Response response = forwardToBlockingService(rpcRequest,
					blockingService, httpRpcController);
			rpcCallback.run(response);
			return;
		}

		// Now try Service
		Service service = serviceMap.get(rpcRequest.getServiceName());
		if (service == null) {
			throw new RpcException(ErrorReason.SERVICE_NOT_FOUND,
					"Could not find service: " + rpcRequest.getServiceName(),
					null);
		}

		// Call service using wrapper around rpcCallback
		RpcCallback<Message> callback = new RpcCallback<Message>() {
			@Override
			public void run(Message response) {
				rpcCallback.run(createRpcResponse(response, true,
						httpRpcController));
			}
		};
		forwardToService(rpcRequest, callback, service, httpRpcController);
	}

	private Response forwardToBlockingService(Request rpcRequest,
			BlockingService blockingService, HttpRpcController httpRpcController) throws RpcException {
		// Get matching method
		MethodDescriptor method = getMethod(rpcRequest,
				blockingService.getDescriptorForType());

		// Create request for method
		Message request = getRequestProto(rpcRequest,
				blockingService.getRequestPrototype(method),
				httpRpcController);

		// Call method
		try {
			Message response = blockingService.callBlockingMethod(method,
					httpRpcController, request);
			return createRpcResponse(response, true, httpRpcController);
		} catch (ServiceException e) {
			throw new RpcException(ErrorReason.RPC_FAILED, e.getMessage(), e);
		} catch (RuntimeException e) {
			throw new RpcException(ErrorReason.RPC_ERROR,
					"Error running method " + method.getFullName(), e);
		}
	}

	private void forwardToService(HttpRpcProtos.Request rpcRequest,
			RpcCallback<Message> callback, Service service,
			HttpRpcController httpRpcController) throws RpcException {
		// Get matching method
		MethodDescriptor method = getMethod(rpcRequest,
				service.getDescriptorForType());

		// Create request for method
		Message request = getRequestProto(rpcRequest,
				service.getRequestPrototype(method), httpRpcController);

		// Call method
		try {
			service.callMethod(method, httpRpcController, request, callback);
		} catch (RuntimeException e) {
			throw new RpcException(ErrorReason.RPC_ERROR,
					"Error running method " + method.getFullName(), e);
		}
	}

	/**
	 * Get matching method.
	 */
	private MethodDescriptor getMethod(HttpRpcProtos.Request rpcRequest,
			ServiceDescriptor descriptor) throws RpcException {
		MethodDescriptor method = descriptor.findMethodByName(rpcRequest
				.getMethodName());
		if (method == null) {
			throw new RpcException(ErrorReason.METHOD_NOT_FOUND, String.format(
					"Could not find method %s in service %s",
					rpcRequest.getMethodName(), descriptor.getFullName()), null);
		}
		return method;
	}

	/**
	 * Get request protobuf for the RPC method.
	 */
	private Message getRequestProto(HttpRpcProtos.Request rpcRequest,
			Message requestPrototype,
			HttpRpcController httpRpcController) throws RpcException {
		Message.Builder builder;
		try {
			
			if (rpcRequest.hasRequestFlag() && (rpcRequest.getRequestFlag() & HttpRpcController.RPC_FLAG_ENCRYPT) != 0) {
				InputStream input = new ByteArrayInputStream(rpcRequest.getRequestProto().toByteArray());
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				try {
					AESCrypto.decryptFile(input, output, httpRpcController.getSign());
				} catch(Exception e) {
					throw new RpcException(ErrorReason.BAD_REQUEST_PROTO,
							"Invalid request proto", null);
				}
				builder = requestPrototype.newBuilderForType().mergeFrom(output.toByteArray());
				httpRpcController.setFlag(rpcRequest.getRequestFlag());
			} else {	
				builder = requestPrototype.newBuilderForType().mergeFrom(
						rpcRequest.getRequestProto());
			}
			
			httpRpcController.setClientVersion(rpcRequest.getClientVersion());
			httpRpcController.setPackageName(rpcRequest.hasPackageName() ? rpcRequest.getPackageName() : null);
			httpRpcController.setChannel(rpcRequest.hasChannel() ? rpcRequest.getChannel() : null);

			if (!builder.isInitialized()) {
				throw new RpcException(ErrorReason.BAD_REQUEST_PROTO,
						"Invalid request proto", null);
			}
		} catch (InvalidProtocolBufferException e) {
			throw new RpcException(ErrorReason.BAD_REQUEST_PROTO,
					"Invalid request proto", e);
		}
		return builder.build();
	}

	/**
	 * Create RPC response protobuf from method invocation results.
	 */
	private HttpRpcProtos.Response createRpcResponse(Message response,
			boolean callbackInvoked, HttpRpcController httpRpcController) {
		Builder responseBuilder = HttpRpcProtos.Response.newBuilder();
		if (response != null) {
			if ((httpRpcController.getFlag() & HttpRpcController.RPC_FLAG_ENCRYPT) != 0) {
				InputStream input = new ByteArrayInputStream(response.toByteArray());
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				try {
					AESCrypto.encryptFile(input, output, httpRpcController.getSign(), false);
					responseBuilder.setCallback(true).setResponseFlag(httpRpcController.getFlag()).setResponseProto(ByteString.copyFrom(output.toByteArray()));
				} catch(Exception e) {
					httpRpcController.setFailed("invalide_response_proto", ErrorReason.RPC_FAILED);
				}
			} else {			
				responseBuilder.setCallback(true).setResponseProto(
						response.toByteString());
			}
		} else {
			// Set whether callback was called (in case of async)
			responseBuilder.setCallback(callbackInvoked);
		}
		if (httpRpcController.failed()) {
			responseBuilder.setError(httpRpcController.errorText());
			responseBuilder.setErrorReason(ErrorReason.RPC_FAILED);
		}
		return responseBuilder.build();
	}

	/**
	 * Callback that just saves the response and the fact that it was invoked.
	 */
	static class Callback<T extends Message> implements RpcCallback<T> {

		private T response = null;
		private boolean invoked = false;

		@Override
		public void run(T response) {
			this.response = response;
			invoked = true;
		}

		public T getResponse() {
			return response;
		}

		public boolean isInvoked() {
			return invoked;
		}
	}

	/**
	 * Signifies error while handling RPC.
	 */
	static class RpcException extends Exception {

		public final ErrorReason errorReason;
		public final String msg;

		public RpcException(ErrorReason errorReason, String msg, Exception cause) {
			super(msg, cause);
			this.errorReason = errorReason;
			this.msg = msg;
		}
	}

}
