package com.spring.batch_microservice.exception;

public class CustomRetryableException extends Exception {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	public CustomRetryableException() {
		super();
	}

	public CustomRetryableException(String msg) {
		super(msg);
	}
}
