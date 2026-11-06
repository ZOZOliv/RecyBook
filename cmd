git clone -b claude/book-management-app-m6zs39 https://ZOZOliv:***@github.com/ZOZOliv/RecyBook.git
docker build -t recybook ./RecyBook
docker run -d -p 8080:3000 -v recybook-data:/app/data --name recybook recybook
