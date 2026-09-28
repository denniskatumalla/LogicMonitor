# TLS_Certificate_Expiry-

## Contract

```
Module:          TLS_Certificate_Expiry-
Applies to:      resources with property tls.endpoints set
Input property:  tls.endpoints = host[:port],host[:port],...   (port defaults to 443)
Instances:       one per endpoint, wildvalue = host:port
Datapoints:      handshakeOk (gauge), daysUntilExpiry (gauge),
                 daysSinceIssued (gauge), chainLength (gauge),
                 chainTrusted (gauge)
Interval:        3600s collection, 3600s discovery
```

<!-- Step 13: why a DataSource vs Website check, AppliesTo, thresholds (30/14/7),
     collector requirements, known limitations, how to import. -->
