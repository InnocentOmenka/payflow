package com.innocodes.payflow.merchant.membership;

import java.util.List;

public record MembershipPageResponse(List<MembershipResponse> content, int page, int size,
                                     long totalElements, int totalPages) {}
