FROM python:3.12-slim

# Install system dependencies
# ffmpeg is required for waveform generation
# gcc/build-essential might be needed for some python packages like bcrypt
RUN apt-get update && apt-get install -y \
    ffmpeg \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install Python dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY . .

# Overwrite config with Docker-specific version
COPY config.docker.yaml config.yaml

# Copy music files (tracks)
COPY music /app/music

# Remove host-specific library.db if it was copied, and re-import
RUN rm -f /app/music/library.db && \
    beet -c config.yaml import -qC /app/music

# Create necessary directories for runtime
RUN mkdir -p uploads waveforms

# Expose the port
EXPOSE 8338

# Run the server
CMD ["python", "upload_server.py"]
