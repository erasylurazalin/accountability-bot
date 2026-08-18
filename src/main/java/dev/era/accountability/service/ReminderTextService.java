package dev.era.accountability.service;

import dev.era.accountability.domain.Commitment;
import dev.era.accountability.repo.ReminderTextRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ReminderTextService {

    private static final Logger log = LoggerFactory.getLogger(ReminderTextService.class);

    private final ReminderTextRepository texts;

    public ReminderTextService(ReminderTextRepository texts) {
        this.texts = texts;
    }

    /**
     * Pre-generated line if the batch left one, static template otherwise.
     * Never calls out to a model — NOTES.md section 5.1 rejects doing that on
     * the send path, since it makes the reminder depend on a third party being
     * up at exactly the moment it matters.
     */
    public String textFor(Commitment commitment, int urgencyTier) {
        try {
            var generated = texts.consumeOne(commitment.id(), urgencyTier);
            if (generated.isPresent()) {
                return generated.get().body();
            }
        } catch (RuntimeException e) {
            log.warn("pre-generated text lookup failed for commitment {}, using static template",
                    commitment.id(), e);
        }
        return StaticTemplates.pick(commitment, urgencyTier);
    }
}
