package com.papeleria.ledger;

import com.papeleria.common.Source;

public record LedgerEntry(long id, long customerId, EntryType type, long amountCents,
                          long balanceAfterCents, Long reversesEntryId, String note, Source source,
                          String actor, String actorLogin, String createdAt, String idempotencyKey) {}
