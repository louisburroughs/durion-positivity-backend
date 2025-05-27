package com.positivity.posimage.model;

import jakarta.persistence.*;
import java.util.List;

@Entity
@Table(name = "image")
public class ImageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String filename;
    private String url;
    @ElementCollection
    private List<String> context;
    @Enumerated(EnumType.STRING)
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
