package com.innocodes.payflow.merchant.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record MerchantStatusRequest(@NotNull @PositiveOrZero Long version) {}
