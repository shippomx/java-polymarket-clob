package com.polymarket.clob.onboard;

import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.gamma.GammaSession;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.order.PostOrderResponse;

import java.util.Optional;

public record OnboardingResult(
        Address wallet,
        ApiCredentials creds,
        GammaSession session,
        Optional<PostOrderResponse> testOrder
) {}
