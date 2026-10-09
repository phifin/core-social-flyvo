package com.flyvo.socialcore.identity.application.port;

import com.flyvo.socialcore.identity.domain.IdentityState.ProviderProof;

public interface ProviderClient {
  String authorization(String provider, String state, String nonce, String verifier);

  ProviderProof verify(String provider, String code, byte[] expectedNonce, String verifier);
}
