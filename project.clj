(defproject portal "0.1.0-SNAPSHOT"
    :description "A sample project"
  
  :url "http://example.org/sample-clojure-project"  

  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :dependencies [[org.clojure/clojure "1.5.1"]
;                 [org.clojure/java.jdbc "0.3.6"]
;                 [mysql/mysql-connector-java "5.1.25"]
;                 [korma "0.3.0"]
                 [http-kit "2.1.16"]
                 [ring "1.5.0"]
                 [compojure "1.1.5"]]
  :native-path "lib"
  :repositories {"java.net" "http://download.java.net/maven/2"
                 "mvn" "http://mvnrepository.com/"
                 "repo1" "http://repo1.maven.org/maven2/"
                 })





