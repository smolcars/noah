ALTER TABLE users
ADD COLUMN display_name TEXT,
ADD CONSTRAINT users_display_name_length CHECK (
    display_name IS NULL OR char_length(display_name) <= 80
);
