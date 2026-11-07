package mom;

/*
 * Tudo o que os componentes precisam saber para se comunicar:
 * o endereço do Broker e os nomes da fila e do tópico.
 * Nenhum componente conhece o endereço dos outros, apenas o do servidor do middleware.
 */
public final class Config {

	/*
	 * URL do servidor JMS (ActiveMQ) em localhost, porta padrão 61616.
	 * Pode ser trocada pela propriedade -Dbroker.url=tcp://outro-host:61616
	 *
	 * queuePrefetch=1 faz o Broker entregar uma linha por vez a cada Consumidor.
	 * Sem isso, o ActiveMQ envia até 1000 mensagens de uma vez para o primeiro
	 * Consumidor conectado e os demais ficam ociosos, perdendo o paralelismo.
	 */
	public static final String URL = System.getProperty("broker.url",
			"tcp://localhost:61616?jms.prefetchPolicy.queuePrefetch=1");

	/* Fila de Linhas: ponto a ponto, cada linha é entregue a um único Contador */
	public static final String FILA_LINHAS = "FILA_LINHAS";

	/* Tópico de Palavras: publish/subscribe, assinado pelo Dashboard */
	public static final String TOPICO_PALAVRAS = "TOPICO_PALAVRAS";

	/* Propriedades enviadas junto com as mensagens */
	public static final String PROP_EXECUCAO = "execucao";
	public static final String PROP_PALAVRAS = "palavras";
	public static final String PROP_LINHA = "linha";
	public static final String PROP_CONTADOR = "contador";

	private Config() {
	}
}
