# --- !Ups

CREATE TABLE `licenses` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `subdomain` varchar(20) NOT NULL,
  `name` varchar(100) NOT NULL,
  `email` varchar(100) NOT NULL,
  `signup` datetime NOT NULL,
  `trial` int(11) NOT NULL,
  `trial_ends` datetime NOT NULL,
  `password` char(102) NOT NULL,
  `password_token` varchar(50) DEFAULT NULL,
  `password_token_expiration` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `subdomain` (`subdomain`),
  UNIQUE KEY `email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `servers` (
  `id` varchar(100) NOT NULL,
  `status` varchar(20) NOT NULL,
  `public_dns` varchar(100) NOT NULL,
  `online` varchar(10) NOT NULL,
  `online_test` varchar(10) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `serving_listeners` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `sender_subdomain` varchar(50) NOT NULL,
  `server_id` varchar(100) NOT NULL,
  `polling` int(11) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `sender_subdomain` (`sender_subdomain`),
  KEY `server_id` (`server_id`),
  CONSTRAINT `serving_listeners_ibfk_1` FOREIGN KEY (`server_id`) REFERENCES `servers` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

INSERT INTO `servers` (`id`, `status`, `public_dns`, `online`, `online_test`) VALUES ('pulp1',  'test', 'pulp1.localhost.localdomain:9000', 'true',  'false');

CREATE TABLE `subdomain_log` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `subdomain` varchar(20) NOT NULL,
  `ip` varchar(20) NOT NULL,
  `timestamp` datetime NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;


# --- !Downs

DROP TABLE `licenses`;

DROP TABLE `serving_listeners`;

DROP TABLE `servers`;

DROP TABLE `subdomain_log`;
