package com.positivity.bulkloader.internal.parser;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Structure-discovering parser for YAML files: records are taken from the root sequence, or from
 * the largest sequence of mappings found anywhere in the document. Uses SnakeYAML's safe
 * constructor so uploaded files cannot instantiate arbitrary types.
 */
@Component
public class YamlRecordFileParser implements RecordFileParser {

    private static final Set<String> EXTENSIONS = Set.of("yaml", "yml");

    @Override
    public boolean supports(@NonNull String fileName) {
        return EXTENSIONS.contains(FileNames.extension(fileName));
    }

    @Override
    @NonNull
    public RecordSource open(@NonNull InputStream content, @NonNull String fileName) {
        try (content) {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object tree = yaml.load(content);
            return StructuredRecords.toRecordSource(tree, fileName);
        } catch (YAMLException e) {
            throw new RecordFileParseException("Failed to parse YAML file: " + fileName, e);
        } catch (IOException e) {
            throw new RecordFileParseException("Failed to read YAML file: " + fileName, e);
        }
    }
}
