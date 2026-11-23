package com.papeleria.customer;

public record Customer(long id, String code, String name, String phone, String document, String notes,
                       boolean active, long version, long balanceCents, String lastEntryAt) {}
