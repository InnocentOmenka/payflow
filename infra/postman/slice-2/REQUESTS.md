# Postman request matrix

All URLs below resolve from the committed local template. API requests send `Accept: application/json`; POST adds `Content-Type: application/json`. Payment POST additionally sends `Idempotency-Key: postman-slice2-denied`. Bodies and auth setup are in README.md and the collection. Never send an Origin header.

| Request | Method | URL | Authentication | Expected | Body |
| --- | --- | --- | --- | --- | --- |
| Merchant health | GET | `http://localhost:8086/actuator/health` | No Auth | 200 | None |
| Payment health | GET | `http://localhost:8081/actuator/health` | No Auth | 200 | None |
| Merchant anonymous create | POST | `http://localhost:8086/api/v1/merchants` | No Auth | 401 | JSON body |
| Merchant anonymous read | GET | `http://localhost:8086/api/v1/merchants/00000000-0000-0000-0000-000000000001` | No Auth | 401 | None |
| Payment anonymous create | POST | `http://localhost:8081/api/v1/payments` | No Auth | 401 | JSON body |
| Payment anonymous read | GET | `http://localhost:8081/api/v1/payments/00000000-0000-0000-0000-000000000001` | No Auth | 401 | None |
| Merchant valid JWT create denied | POST | `http://localhost:8086/api/v1/merchants` | {{merchant_token}} | 403 | JSON body |
| Merchant valid JWT read denied | GET | `http://localhost:8086/api/v1/merchants/00000000-0000-0000-0000-000000000001` | {{merchant_token}} | 403 | None |
| Payment valid JWT create denied | POST | `http://localhost:8081/api/v1/payments` | {{payment_token}} | 403 | JSON body |
| Payment valid JWT read denied | GET | `http://localhost:8081/api/v1/payments/00000000-0000-0000-0000-000000000001` | {{payment_token}} | 403 | None |
| Merchant invalid JWT | GET | `http://localhost:8086/api/v1/merchants` | not-a-valid-jwt | 401 | None |
| Merchant wrong audience | GET | `http://localhost:8086/api/v1/merchants` | {{payment_token}} | 401 | None |
| Merchant invalid bearer on health | GET | `http://localhost:8086/actuator/health` | not-a-valid-jwt | 401 | None |
| Payment invalid JWT | GET | `http://localhost:8081/api/v1/payments` | not-a-valid-jwt | 401 | None |
| Payment wrong audience | GET | `http://localhost:8081/api/v1/payments` | {{merchant_token}} | 401 | None |
| Payment invalid bearer on health | GET | `http://localhost:8081/actuator/health` | not-a-valid-jwt | 401 | None |
| Merchant anonymous /actuator/info | GET | `http://localhost:8086/actuator/info` | No Auth | 401 | None |
| Merchant valid JWT /actuator/info | GET | `http://localhost:8086/actuator/info` | {{merchant_token}} | 403 | None |
| Merchant anonymous /actuator | GET | `http://localhost:8086/actuator` | No Auth | 401 | None |
| Merchant valid JWT /actuator | GET | `http://localhost:8086/actuator` | {{merchant_token}} | 403 | None |
| Merchant anonymous /actuator/health/readiness | GET | `http://localhost:8086/actuator/health/readiness` | No Auth | 401 | None |
| Merchant valid JWT /actuator/health/readiness | GET | `http://localhost:8086/actuator/health/readiness` | {{merchant_token}} | 403 | None |
| Merchant anonymous /not-a-real-route | GET | `http://localhost:8086/not-a-real-route` | No Auth | 401 | None |
| Merchant valid JWT /not-a-real-route | GET | `http://localhost:8086/not-a-real-route` | {{merchant_token}} | 403 | None |
| Payment anonymous /actuator/info | GET | `http://localhost:8081/actuator/info` | No Auth | 401 | None |
| Payment valid JWT /actuator/info | GET | `http://localhost:8081/actuator/info` | {{payment_token}} | 403 | None |
| Payment anonymous /actuator | GET | `http://localhost:8081/actuator` | No Auth | 401 | None |
| Payment valid JWT /actuator | GET | `http://localhost:8081/actuator` | {{payment_token}} | 403 | None |
| Payment anonymous /actuator/health/readiness | GET | `http://localhost:8081/actuator/health/readiness` | No Auth | 401 | None |
| Payment valid JWT /actuator/health/readiness | GET | `http://localhost:8081/actuator/health/readiness` | {{payment_token}} | 403 | None |
| Payment anonymous /not-a-real-route | GET | `http://localhost:8081/not-a-real-route` | No Auth | 401 | None |
| Payment valid JWT /not-a-real-route | GET | `http://localhost:8081/not-a-real-route` | {{payment_token}} | 403 | None |
