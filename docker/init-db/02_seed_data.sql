-- Seed data for local development.
-- Real IBGE codes used as municipality PKs.

-- Region: Sudeste
INSERT INTO regions (id, name, abbreviation) VALUES (3, 'Sudeste', 'SE');

-- State: Rio de Janeiro
INSERT INTO states (id, name, abbreviation, region_id) VALUES (33, 'Rio de Janeiro', 'RJ', 3);

-- Municipalities (real IBGE codes)
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

-- Categories (initial set matching CSV columns)
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
