/**
 * Root API for the outbox framework — a minimal, Spring-free transactional outbox
 * with JDBC persistence and at-least-once delivery.
 *
 * <h2>Core Design</h2>
 * <p>Events are written to a database table <em>within</em> the caller's transaction via
 * {@link outbox.OutboxWriter}. After commit, the configured {@link outbox.WriterHook} pushes events
 * to the {@linkplain outbox.dispatch.OutboxDispatcher dispatcher}'s hot queue for immediate
 * delivery. A scheduled {@linkplain outbox.poller.OutboxPoller poller} acts as a fallback,
 * sweeping events that missed the hot path. The two paths are drained with a fair 2:1
 * weighted round-robin (hot&nbsp;2/3, cold&nbsp;1/3).
 *
 * <p>Listeners are routed by {@code (aggregateType, eventType)} via a
 * {@linkplain outbox.registry.ListenerRegistry registry}. Events with no matching listener
 * are marked DEAD immediately. Delivery is at-least-once; downstream systems must deduplicate
 * by {@link outbox.EventEnvelope#eventId() eventId}.
 *
 * <h2>Module Layout</h2>
 * <ul>
 *   <li><b>outbox-core</b> — interfaces, dispatcher, poller, registries (zero external deps)</li>
 *   <li><b>outbox-jdbc</b> — {@linkplain outbox.jdbc JDBC event store hierarchy}
 *       (H2, MySQL, PostgreSQL)</li>
 *   <li><b>outbox-spring-adapter</b> — optional {@linkplain outbox.spring Spring transaction
 *       integration}</li>
 * </ul>
 *
 * <h2>Quick Start (composite builder)</h2>
 * <pre>{@code
 * var outboxStore  = JdbcOutboxStores.detect(dataSource);
 * var connProvider = new DataSourceConnectionProvider(dataSource);
 * var txContext     = new ThreadLocalTxContext();
 * var registry     = new DefaultListenerRegistry()
 *     .register("OrderPlaced", event ->
 *         System.out.println("Received: " + event.payloadJson()));
 *
 * try (Outbox outbox = Outbox.singleNode()
 *     .connectionProvider(connProvider)
 *     .txContext(txContext)
 *     .outboxStore(outboxStore)
 *     .listenerRegistry(registry)
 *     .build()) {
 *
 *     var txManager = new JdbcTransactionManager(connProvider, txContext);
 *     var writer    = outbox.writer();
 *
 *     try (var tx = txManager.begin()) {
 *         writer.write(EventEnvelope.builder("OrderPlaced")
 *             .aggregateType("Order")
 *             .aggregateId("order-123")
 *             .payloadJson("{\"orderId\":\"order-123\",\"amount\":99.99}")
 *             .build());
 *         tx.commit();
 *     }
 * }
 * }</pre>
 *
 * <h2>Manual Wiring (advanced)</h2>
 * <pre>{@code
 * var outboxStore  = JdbcOutboxStores.detect(dataSource);
 * var connProvider = new DataSourceConnectionProvider(dataSource);
 * var txContext     = new ThreadLocalTxContext();
 * var registry     = new DefaultListenerRegistry()
 *     .register("OrderPlaced", event ->
 *         System.out.println("Received: " + event.payloadJson()));
 *
 * var dispatcher = OutboxDispatcher.builder()
 *     .connectionProvider(connProvider)
 *     .outboxStore(outboxStore)
 *     .listenerRegistry(registry)
 *     .build();
 *
 * var poller = OutboxPoller.builder()
 *     .connectionProvider(connProvider)
 *     .outboxStore(outboxStore)
 *     .handler(new DispatcherPollerHandler(dispatcher))
 *     .build();
 * poller.start();
 *
 * var txManager = new JdbcTransactionManager(connProvider, txContext);
 * var writer    = new OutboxWriter(txContext, outboxStore,
 *                     new DispatcherWriterHook(dispatcher));
 *
 * try (var tx = txManager.begin()) {
 *     writer.write(EventEnvelope.builder("OrderPlaced")
 *         .aggregateType("Order")
 *         .aggregateId("order-123")
 *         .payloadJson("{\"orderId\":\"order-123\",\"amount\":99.99}")
 *         .build());
 *     tx.commit();
 * }
 *
 * poller.close();
 * dispatcher.close();
 * }</pre>
 *
 * <h2>Spring Boot Usage</h2>
 * <pre>{@code
 * // Inside a @Transactional method:
 * outboxWriter.write(EventEnvelope.builder("UserCreated")
 *     .aggregateType("User")
 *     .aggregateId(userId)
 *     .payloadJson(JsonCodec.getDefault().toJson(Map.of("userId", userId, "name", name)))
 *     .build());
 * }</pre>
 *
 * @see outbox.Outbox
 * @see outbox.OutboxWriter
 * @see outbox.EventEnvelope
 * @see outbox.EventType
 * @see outbox.AggregateType
 * @see outbox.EventListener
 * @see outbox.WriterHook
 */
package outbox;
