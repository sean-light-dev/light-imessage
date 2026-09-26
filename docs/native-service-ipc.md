# Native service IPC protocol

The native service and Kotlin client exchange UTF-8 JSON in 4-byte big-endian length-prefixed frames. Commands remain serialized: each command has one lockstep reply, in order, with no correlation ID. Existing command and reply frames (`PING`/`PONG`, `ACTIVATE`/`ACTIVATION_STATUS`, `SEND_MESSAGE`/`ACK`, `ERROR`) are unchanged.

The service may also send unsolicited frames at any time, including while a command is in flight. These event frames are demultiplexed from command replies and are exposed through `INativeServiceClient.observeEvents()`:

```json
{"type":"MESSAGE_RECEIVED","message_id":"m1","sender":"alice@example.com","timestamp":123,"envelope":"AQI="}
{"type":"DELIVERY_RECEIPT","message_id":"m1","delivery_receipt_at":456}
```

`MESSAGE_RECEIVED.envelope` is a base64-encoded encrypted envelope. Kotlin decodes it to the existing `PushMessage` model and uses the regular decrypt/persist pipeline. Since this frame arrived on the trusted native IPC channel, it does not require a UnifiedPush distributor token; actual distributor pushes continue to be validated against their registration. `DELIVERY_RECEIPT` timestamps are Unix epoch milliseconds and update only the matching outgoing message's status and `deliveryReceiptAt`.
