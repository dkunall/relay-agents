package com.relayagents.trigger.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relayagents.run.application.RunRecord;
import com.relayagents.run.application.RunService;
import com.relayagents.run.domain.BudgetLimits;
import com.relayagents.secret.application.SecretResolver;
import com.relayagents.shared.domain.Actor;
import com.relayagents.shared.error.ForbiddenException;
import com.relayagents.shared.error.InvalidRequestException;
import com.relayagents.shared.error.NotFoundException;
import com.relayagents.shared.json.Json;
import com.relayagents.workflow.application.WorkflowService;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TriggerService {

    static final Duration SIGNATURE_TOLERANCE = Duration.ofMinutes(5);
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final TriggerRepository triggers;
    private final WorkflowService workflows;
    private final RunService runs;
    private final SecretResolver secrets;
    private final Json json;
    private final Clock clock;

    public TriggerService(TriggerRepository triggers, WorkflowService workflows, RunService runs,
                          SecretResolver secrets, Json json, Clock clock) {
        this.triggers = triggers;
        this.workflows = workflows;
        this.runs = runs;
        this.secrets = secrets;
        this.json = json;
        this.clock = clock;
    }

    public Trigger create(Actor actor, UUID workflowId, Trigger.Type type, String secretName, String cron,
                          JsonNode input, BudgetLimits budget) {
        workflows.get(actor.tenant(), workflowId, null);
        JsonNode baseInput = input == null || input.isNull() ? json.object() : input;
        if (!baseInput.isObject()) {
            throw new InvalidRequestException("invalid-input", "Trigger input must be a JSON object");
        }
        Instant nextFire = null;
        if (type == Trigger.Type.WEBHOOK) {
            SecretResolver.checkName(secretName);
            if (secrets.resolve(secretName).isEmpty()) {
                throw new InvalidRequestException("unknown-secret", "Secret " + secretName + " is not configured");
            }
            cron = null;
        } else {
            nextFire = nextFire(parseCron(cron), clock.instant());
            secretName = null;
        }
        Trigger trigger = new Trigger(UUID.randomUUID(), actor.tenant(), workflowId, type, secretName, cron,
            baseInput, budget == null ? RunService.DEFAULT_BUDGET : budget, true, nextFire, actor.subject(),
            clock.instant());
        triggers.insert(trigger);
        return trigger;
    }

    public List<Trigger> list(Actor actor) {
        return triggers.list(actor.tenant());
    }

    public void setEnabled(Actor actor, UUID id, boolean enabled) {
        triggers.find(actor.tenant(), id)
            .orElseThrow(() -> new NotFoundException("trigger-not-found", "No trigger " + id));
        triggers.setEnabled(actor.tenant(), id, enabled);
    }

    /**
     * Verifies {@code X-Relay-Signature: sha256=<hex hmac of "timestamp.body">} and starts a run. The delivery
     * id doubles as the idempotency key, so a sender retrying the same delivery gets the same run back.
     */
    public RunRecord deliver(UUID triggerId, String timestamp, String signature, String deliveryId, byte[] body) {
        Trigger trigger = triggers.findById(triggerId).filter(t -> t.type() == Trigger.Type.WEBHOOK)
            .orElseThrow(() -> new ForbiddenException("bad-signature", "Signature check failed"));
        if (body.length > MAX_PAYLOAD_BYTES) {
            throw new InvalidRequestException("payload-too-large", "Webhook payload is limited to 64 KiB");
        }
        verify(trigger, timestamp, signature, body);
        if (!trigger.enabled()) {
            throw new ForbiddenException("trigger-disabled", "Trigger is disabled");
        }
        if (deliveryId == null || deliveryId.isBlank() || deliveryId.length() > 100) {
            throw new InvalidRequestException("missing-delivery-id", "X-Relay-Delivery is required");
        }
        JsonNode payload;
        try {
            payload = json.read(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new InvalidRequestException("invalid-payload", "Webhook payload must be JSON");
        }
        ObjectNode input = ((ObjectNode) trigger.input().deepCopy());
        input.set("payload", payload);
        Actor actor = Actor.system(trigger.tenant(), "trigger:" + trigger.id());
        return runs.start(actor, trigger.workflowId(), null, input, trigger.budget(),
            "hook:" + trigger.id() + ":" + deliveryId).run();
    }

    /**
     * Claims due cron triggers and moves them to their next fire time in one short transaction. Starting the
     * runs happens afterwards, so a quota rejection skips that tick instead of retrying it forever.
     */
    @Transactional
    public List<Fire> claimDue(int limit) {
        Instant now = clock.instant();
        return triggers.lockDue(now, limit).stream().map(t -> {
            triggers.setNextFire(t.id(), nextFire(parseCron(t.cron()), now));
            return new Fire(t, t.nextFireAt());
        }).toList();
    }

    public record Fire(Trigger trigger, Instant scheduledAt) {
    }

    public RunRecord fire(Fire fire) {
        Trigger t = fire.trigger();
        Actor actor = Actor.system(t.tenant(), "trigger:" + t.id());
        return runs.start(actor, t.workflowId(), null, t.input(), t.budget(),
            "cron:" + t.id() + ":" + fire.scheduledAt().getEpochSecond()).run();
    }

    private void verify(Trigger trigger, String timestamp, String signature, byte[] body) {
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp);
        } catch (NumberFormatException | NullPointerException e) {
            throw new ForbiddenException("bad-signature", "Signature check failed");
        }
        Duration skew = Duration.between(Instant.ofEpochSecond(sentAt), clock.instant()).abs();
        if (skew.compareTo(SIGNATURE_TOLERANCE) > 0) {
            throw new ForbiddenException("stale-signature", "Timestamp is outside the 5 minute window");
        }
        String key = secrets.resolve(trigger.secretName())
            .orElseThrow(() -> new ForbiddenException("bad-signature", "Signature check failed"));
        byte[] expected = ("sha256=" + hmac(key, timestamp + "." + new String(body, StandardCharsets.UTF_8)))
            .getBytes(StandardCharsets.UTF_8);
        byte[] given = signature == null ? new byte[0] : signature.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, given)) {
            throw new ForbiddenException("bad-signature", "Signature check failed");
        }
    }

    public static String hmac(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    static CronExpression parseCron(String cron) {
        if (cron == null || !CronExpression.isValidExpression(cron)) {
            throw new InvalidRequestException("invalid-cron", "Not a valid cron expression: " + cron);
        }
        return CronExpression.parse(cron);
    }

    static Instant nextFire(CronExpression cron, Instant after) {
        var next = cron.next(after.atZone(ZoneOffset.UTC));
        if (next == null) {
            throw new InvalidRequestException("invalid-cron", "Cron expression never fires");
        }
        Instant at = next.toInstant();
        // Second-level schedules would let one trigger flood a tenant's quota.
        if (Duration.between(after, cron.next(next).toInstant()).compareTo(Duration.ofMinutes(1)) < 0) {
            throw new InvalidRequestException("cron-too-frequent", "Cron triggers fire at most once a minute");
        }
        return at;
    }
}
