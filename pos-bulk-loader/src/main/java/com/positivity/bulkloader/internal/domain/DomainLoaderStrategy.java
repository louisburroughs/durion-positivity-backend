package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

public interface DomainLoaderStrategy<T> {

    DomainType getDomainType();

    T mapRow(@NonNull Map<String, String> row);

    List<String> validate(@NonNull T item);
}
