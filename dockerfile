FROM node:alpine

WORKDIR /usr/src/app

COPY . /usr/src/app

RUN npm install -g @angular/cli
RUN npm install
RUN ng build

EXPOSE 80
CMD [ "node", "server.js"]
