-- Combined API schema + seed for Testcontainers.
-- Replicates tables managed by Knex (roubometro-back).

CREATE TABLE regions (
  id BIGINT PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  abbreviation VARCHAR(2) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE states (
  id BIGINT PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  abbreviation VARCHAR(2) NOT NULL,
  region_id BIGINT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (region_id) REFERENCES regions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE municipalities (
  id BIGINT PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  state_id BIGINT NOT NULL,
  region VARCHAR(255) NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (state_id) REFERENCES states(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(255) UNIQUE NOT NULL,
  cpf VARCHAR(11) UNIQUE NOT NULL,
  name VARCHAR(255) NOT NULL,
  state_id BIGINT NULL,
  municipality_id BIGINT NULL,
  phone VARCHAR(30) NULL,
  active BOOLEAN DEFAULT FALSE,
  profile ENUM('user','moderator') DEFAULT 'user',
  token_temp VARCHAR(255) NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (state_id) REFERENCES states(id),
  FOREIGN KEY (municipality_id) REFERENCES municipalities(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE categories (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE monthly_stats (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  municipality_id BIGINT NOT NULL,
  year SMALLINT NOT NULL,
  month TINYINT NOT NULL,
  category_id BIGINT NOT NULL,
  category_value INT NOT NULL,
  source_file VARCHAR(255),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_mun_date_category (municipality_id, year, month, category_id),
  INDEX idx_mun_date_category (municipality_id, year, month, category_id),
  FOREIGN KEY (municipality_id) REFERENCES municipalities(id),
  FOREIGN KEY (category_id) REFERENCES categories(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_reports (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  municipality_id BIGINT NOT NULL,
  reported_at DATETIME NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  category_id BIGINT NOT NULL,
  description TEXT,
  status ENUM('pending','validated','rejected') DEFAULT 'pending',
  has_occurred BOOLEAN DEFAULT FALSE,
  moderation_notes TEXT,
  FOREIGN KEY (user_id) REFERENCES users(id),
  FOREIGN KEY (municipality_id) REFERENCES municipalities(id),
  FOREIGN KEY (category_id) REFERENCES categories(id),
  INDEX idx_report_mun_date (municipality_id, reported_at)
);

CREATE TABLE refresh_tokens (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  token_hash CHAR(64) NOT NULL,
  expires_at DATETIME NOT NULL,
  revoked BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  last_used_at DATETIME NULL,
  user_agent VARCHAR(255),
  ip_address VARCHAR(45),
  FOREIGN KEY (user_id) REFERENCES users(id),
  INDEX idx_user_token (user_id),
  INDEX idx_token_hash (token_hash)
);

-- Seed data
INSERT INTO regions (id, name, abbreviation) VALUES (3, 'Sudeste', 'SE');
INSERT INTO states (id, name, abbreviation, region_id) VALUES (33, 'Rio de Janeiro', 'RJ', 3);

INSERT INTO municipalities (id, name, state_id, region) VALUES
  (3300100, 'Angra dos Reis', 33, 'Interior'),
  (3300407, 'Belford Roxo', 33, 'Baixada Fluminense'),
  (3301702, 'Duque de Caxias', 33, 'Baixada Fluminense'),
  (3303302, 'Niteroi', 33, 'Grande Niteroi'),
  (3303500, 'Nova Friburgo', 33, 'Interior'),
  (3303906, 'Paracambi', 33, 'Baixada Fluminense'),
  (3304557, 'Rio de Janeiro', 33, 'Capital'),
  (3304904, 'Sao Goncalo', 33, 'Grande Niteroi'),
  (3305109, 'Sao Joao de Meriti', 33, 'Baixada Fluminense'),
  (3306305, 'Volta Redonda', 33, 'Interior');

INSERT INTO categories (id, name) VALUES
  (1, 'Homicidio doloso'),
  (2, 'Lesao corporal seguida de morte'),
  (3, 'Latrocinio'),
  (4, 'CVLI'),
  (5, 'Homicidio por intervencao policial'),
  (6, 'Letalidade violenta'),
  (7, 'Tentativa de homicidio'),
  (8, 'Lesao corporal dolosa'),
  (9, 'Estupro'),
  (10, 'Homicidio culposo'),
  (11, 'Roubo a transeunte'),
  (12, 'Roubo de celular');
