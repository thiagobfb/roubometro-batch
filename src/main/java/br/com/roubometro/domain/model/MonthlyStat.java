package br.com.roubometro.domain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "monthly_stats")
public class MonthlyStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "municipality_id", nullable = false)
    private Long municipalityId;

    @Column(nullable = false)
    private Short year;

    @Column(nullable = false)
    private Byte month;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "category_value", nullable = false)
    private Integer categoryValue;

    @Column(name = "source_file")
    private String sourceFile;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public MonthlyStat() {}

    public MonthlyStat(Long municipalityId, Short year, Byte month, Long categoryId, Integer categoryValue, String sourceFile) {
        this.municipalityId = municipalityId;
        this.year = year;
        this.month = month;
        this.categoryId = categoryId;
        this.categoryValue = categoryValue;
        this.sourceFile = sourceFile;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMunicipalityId() { return municipalityId; }
    public void setMunicipalityId(Long municipalityId) { this.municipalityId = municipalityId; }

    public Short getYear() { return year; }
    public void setYear(Short year) { this.year = year; }

    public Byte getMonth() { return month; }
    public void setMonth(Byte month) { this.month = month; }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public Integer getCategoryValue() { return categoryValue; }
    public void setCategoryValue(Integer categoryValue) { this.categoryValue = categoryValue; }

    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
