package com.positivity.posimage.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import java.util.List;

@Entity
@Table(name = "image")
@Schema(description = "Represents an image stored in the POS system.")
public class ImageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "Unique identifier of the image", example = "1")
    private Long id;

    @Schema(description = "Filename of the image", example = "logo.png")
    private String filename;

    @Schema(description = "Filesystem or URL path to the image file", example = "/images/logo.png")
    private String url;

    @ElementCollection
    @Schema(description = "Context tags or labels associated with the image")
    private List<String> context;

    @Enumerated(EnumType.STRING)
    @Schema(description = "Classification of the image", implementation = Classification.class)
    private Classification classification;

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public List<String> getContext() { return context; }
    public void setContext(List<String> context) { this.context = context; }
    public Classification getClassification() { return classification; }
    public void setClassification(Classification classification) { this.classification = classification; }
}
