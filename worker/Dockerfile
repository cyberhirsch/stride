# Stride alignment worker. COLMAP and pycolmap are built from source because
# PyPI has no Linux ARM wheels (the Pi 5 is aarch64).

FROM ubuntu:24.04 AS build
ARG COLMAP_VERSION=4.2.1
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      git ca-certificates cmake ninja-build build-essential \
      libboost-program-options-dev libboost-graph-dev libboost-system-dev \
      libeigen3-dev libopenimageio-dev libmetis-dev libgoogle-glog-dev \
      libgtest-dev libgmock-dev libsqlite3-dev libcgal-dev libceres-dev \
      libcurl4-openssl-dev libssl-dev libopenblas-dev \
      python3 python3-dev python3-venv \
 && mkdir -p /usr/include/opencv4
RUN git clone --depth 1 --branch ${COLMAP_VERSION} https://github.com/colmap/colmap /src
RUN cmake -S /src -B /build -GNinja -DCMAKE_BUILD_TYPE=Release \
      -DGUI_ENABLED=OFF -DOPENGL_ENABLED=OFF -DCUDA_ENABLED=OFF -DTESTS_ENABLED=OFF \
      -DCMAKE_INSTALL_PREFIX=/opt/colmap \
 && ninja -C /build install
RUN python3 -m venv /venv \
 && /venv/bin/pip install --no-cache-dir --upgrade pip \
 && CMAKE_PREFIX_PATH=/opt/colmap /venv/bin/pip install --no-cache-dir /src
COPY requirements.txt /tmp/
RUN grep -v pycolmap /tmp/requirements.txt | /venv/bin/pip install --no-cache-dir -r /dev/stdin

FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates python3 \
      libboost-program-options1.83.0 libboost-graph1.83.0 libboost-system1.83.0 \
      libopenimageio2.4t64 libmetis5 libgoogle-glog0v6t64 libgflags2.2 \
      libsqlite3-0 libceres4t64 libcurl4t64 libssl3t64 libopenblas0 libgomp1 \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /opt/colmap /opt/colmap
COPY --from=build /venv /venv
ENV PATH=/venv/bin:/opt/colmap/bin:$PATH LD_LIBRARY_PATH=/opt/colmap/lib \
    STRIDE_WORKDIR=/work PYTHONUNBUFFERED=1
WORKDIR /app
COPY stride_worker stride_worker
RUN python -c "import pycolmap, stride_worker.align; print('pycolmap', pycolmap.__version__)"
VOLUME /work
CMD ["python", "-m", "stride_worker.main"]
