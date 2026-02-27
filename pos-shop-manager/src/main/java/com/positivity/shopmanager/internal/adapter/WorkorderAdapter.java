package com.positivity.shopmanager.internal.adapter;

import java.util.Optional;

public interface WorkorderAdapter {
    Optional<String> findWorkorderByReference(String workorderRef);
}
