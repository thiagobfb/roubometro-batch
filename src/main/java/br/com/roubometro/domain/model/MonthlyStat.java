package br.com.roubometro.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "monthly_stats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
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
}
