(ns xhub-team.infrastructure
  (:require [xhub-team.configuration :as conf]
             [next.jdbc :as jdbc]
             [next.jdbc.sql :as sql])
  (:import  [com.zaxxer.hikari HikariDataSource HikariConfig]
            [jakarta.mail Session Message Transport Message$RecipientType]
            [jakarta.mail.internet InternetAddress MimeMessage]))

(def session
  (let [props (doto (java.util.Properties.)
                (.put "mail.smtp.host" (:host conf/config->smtp))
                (.put "mail.smtp.auth" (:auth-enable conf/config->smtp))
                (.put "mail.smtp.starttls.enable" (:tls-enable conf/config->smtp)))
        session  (Session/getInstance props
                                      (proxy [jakarta.mail.Authenticator] []
                                        (getPasswordAuthentication []
                                          (jakarta.mail.PasswordAuthentication.
                                           (:email conf/config->smtp)
                                           (:password conf/config->smtp)))))]
    session))

(defn send-message [from to subject body]
  (let [message (MimeMessage. session)]
    (.setFrom message (InternetAddress. from))
    (.setRecipient message Message$RecipientType/TO (InternetAddress. to))
    (.setSubject message subject)
    (.setText message body)
    (Transport/send message)))

(defn send-verification-code [email code]
  (send-message
   (:email conf/config->smtp)
   email
   "Подтверждение"
   (str "Код подтверждения " code)))

(def datasource
  (let [config (HikariConfig.)
        database-config (:database conf/config)]
    (.setJdbcUrl config (:url database-config))
    (.setUsername config (:user database-config))
    (.setPassword config (:password database-config))

    (.setMaximumPoolSize config 10)
    (.setMinimumIdle config 2)
    (.setIdleTimeout config 30000)
    (.setMaxLifetime config 1800000)

    (HikariDataSource. config)))

(defn generate-tag-filter [tags]
  (loop [acc "mg.tag_id in ("
         remaining tags]
    (if (= 1 (count remaining))
      (str acc "?" ")")
      (recur (str acc "?,") (rest remaining)))))

(defn generate-order-by [value]
  (condp = value
    0
    "order by created_at asc"
    nil))

(defn build-filters [filters]
  (let [filter-list (remove nil?
                            [(when (:tags filters) (generate-tag-filter (:tags filters)))
                             (when (:name filters) "manga.name ILIKE ?")
                             (when (:order_by filters) (generate-order-by (:order_by filters)))])]
    (loop [acc "where "
           remaining filter-list]
      (if (= 1 (count remaining))
        (str acc (first remaining))
        (recur (str acc (first remaining) " and ") (rest remaining))))))

(defn get-manga-list [filters]
  (let [sql (str "with manga_list as (SELECT DISTINCT ON(manga.id) manga.id, manga.name, manga.description, mp.id
                 FROM manga manga
                 left join manga_page mp ON mp.manga_id = manga.id
                 left join manga_tag mg on mg.manga_id = manga.id "
                 (when (or (:name filters) (:tags filters)) (build-filters filters))
                 " limit ? offset ? )"
                 " select * from manga_list "
                 (generate-order-by (:order_by filters)))
        params (remove nil? (vec (concat (:tags filters) [(:name filters) (:limit filters) (:offset filters) ] )) )]
    (jdbc/execute! datasource (into [sql] params))))

(defn get-manga-by-id [^java.util.UUID uuid]
  (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn ["select m.id, m.name, m.description, m.created_at, array_agg(mp.id) from manga m
                                       left join manga_page mp on m.id = mp.manga_id
                                        where m.id = ?
                                        group by m.id, m.name, m.description, m.created_at" uuid])]
    (jdbc/execute! stmt)))

(defn create-manga [^java.util.UUID uuid name description]
  (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn ["insert into manga (id,name,description) values (?, ?, ?) returning id" uuid name description])]
    (jdbc/execute! stmt))
  )

(defn add-user [id email password]
  (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn ["insert into \"user\" (id, email, password) values (cast(? as uuid), ?, ?)" id email password])]
    (jdbc/execute! stmt)))

(defn find-user [email password]
  (first (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn ["select u.id, u.email, u.password, u.is_author, u.is_prime, u.created_at from \"user\" u where u.email = ? and u.password = ?" email password])]
    (jdbc/execute! stmt))))

(defn busy-email? [email]
  (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn ["select 1 from \"user\" where email = ? " email])]
    (empty? (jdbc/execute! stmt))))
