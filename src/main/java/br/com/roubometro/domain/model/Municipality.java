package br.com.roubometro.domain.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "municipalities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Municipality {

    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "state_id", nullable = false)
    private Long stateId;

    @Column
    private String region;
}
