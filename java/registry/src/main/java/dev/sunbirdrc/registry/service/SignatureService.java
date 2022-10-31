package dev.sunbirdrc.registry.service;

import dev.sunbirdrc.registry.exception.SignatureException;

import java.util.Map;

public interface SignatureService {

	Object sign(Map<String, Object> propertyValue)
			throws SignatureException.UnreachableException, SignatureException.CreationException;

	boolean verify(Object propertyValue)
			throws SignatureException.UnreachableException, SignatureException.VerificationException;

	String getKey(String keyId) throws SignatureException.UnreachableException, SignatureException.KeyNotFoundException;

	boolean isServiceUp() throws SignatureException.UnreachableException;

}
