CREATE TABLE IF NOT EXISTS car_api_make (
  id UUID PRIMARY KEY,
  make_id UUID,
  make_name VARCHAR(255),
  cache_timestamp TIMESTAMP
);
CREATE TABLE IF NOT EXISTS car_api_model (
  id UUID PRIMARY KEY,
  model_id UUID,
  model_name VARCHAR(255),
  make_id UUID,
  cache_timestamp TIMESTAMP,
  CONSTRAINT fk_car_api_model_make FOREIGN KEY (make_id) REFERENCES car_api_make(id)
);
CREATE INDEX IF NOT EXISTS idx_car_api_model_make_id ON car_api_model (make_id);