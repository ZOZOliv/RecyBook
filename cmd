git clone https://ZOZOliv:***@github.com/ZOZOliv/map-app.git
docker build -t map-app ./map-app
docker run -it -p 8080:80 map-app
