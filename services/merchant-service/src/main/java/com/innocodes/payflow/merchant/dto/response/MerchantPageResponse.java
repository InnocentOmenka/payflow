package com.innocodes.payflow.merchant.dto.response;

import java.util.List;

public record MerchantPageResponse(List<MerchantResponse> content, int page, int size,
                                   long totalElements, int totalPages, boolean first, boolean last) {}
