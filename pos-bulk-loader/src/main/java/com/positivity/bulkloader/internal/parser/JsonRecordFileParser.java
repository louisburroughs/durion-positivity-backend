package com.positivity.bulkloader.internal.parser;

import java.io.InputStream;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Structure-discovering parser for JSON files: records are taken from the root array, or from the
 * largest array of objects found anywhere in the document.
 */
@Component
public class JsonRecordFileParser implements RecordFileParser {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Override
    public boolean supports(@NonNull String fileName) {
        return "json".equals(FileNames.extension(fileName));
    }

    @Override
    @NonNull
    public RecordSource open(@NonNull InputStream content, @NonNull String fileName) {
        try (content) {
            Object tree = mapper.readValue(content, Object.class);
            return StructuredRecords.toRecordSource(tree, fileName);
        } catch (JacksonException e) {
            throw new RecordFileParseException("Failed to parse JSON file: " + fileName, e);
        } catch (java.io.IOException e) {
            throw new RecordFileParseException("Failed to read JSON file: " + fileName, e);
        }
    }
}
