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

INSERT INTO `licenses` (`id`, `subdomain`, `name`, `email`, `signup`, `trial`, `trial_ends`, `password`, `password_token`, `password_token_expiration`) VALUES (1, 'pulp', 'John', 'pulp@mailinator.com',  '2016-05-17 14:10:57',  0,  '2016-05-24 14:10:57',  '1000:7dc653160f4e5881b1f9d74ee24255996081f100a89f4649:70fffa9a0f00f4adbea790b168cee9d7037b0dbc9629b5e3', NULL, NULL);

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

INSERT INTO `servers` (`id`, `status`, `public_dns`, `online`, `online_test`) VALUES
  ('pulp1',  'test', 'pulp1.localhost.localdomain:9000', 'true',  'false'),
  ('pulp2', 'test', 'pulp2.localhost.localdomain:9001',  'false', 'false');

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
