package mom;

import java.util.Locale;

import javax.jms.*;
import org.apache.activemq.ActiveMQConnectionFactory;

/*
 * Contador de Palavras (4) - worker.
 * É Consumidor da Fila de Linhas e Publisher do Tópico de Palavras.
 *
 * Cada worker pega a próxima linha disponível na fila, conta as palavras-chave
 * e publica o resultado no tópico. A quantidade de workers é fixa e não depende
 * da quantidade de linhas: os mesmos workers processam o arquivo inteiro.
 *
 * Uso: ContadorPalavras [quantidade de workers]   (padrão: 3)
 */
public class ContadorPalavras implements Runnable {

	private final String nome;

	public ContadorPalavras(String nome) {
		this.nome = nome;
	}

	public static void main(String[] args) {
		int quantidade = args.length > 0 ? Integer.parseInt(args[0]) : 3;

		/*
		 * Cada worker roda em sua própria thread, com sua própria conexão e Session,
		 * pois uma Session JMS não pode ser compartilhada entre threads.
		 * O PID entra no nome para diferenciar workers de processos diferentes.
		 */
		long pid = ProcessHandle.current().pid();
		for (int i = 1; i <= quantidade; i++) {
			new Thread(new ContadorPalavras("contador-" + pid + "-" + i)).start();
		}
	}

	@Override
	public void run() {
		try {

			/*
			 * Estabelecendo conexão com o Servidor JMS
			 */
			ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(Config.URL);
			Connection connection = connectionFactory.createConnection();
			connection.start();

			/*
			 * Criando Session transacionada.
			 * Consumir a linha e publicar a contagem fazem parte da mesma transação:
			 * a linha só sai da fila quando o resultado é publicado (session.commit()).
			 * Se o worker cair no meio, a linha volta para a fila e outro worker a processa.
			 */
			Session session = connection.createSession(true, Session.SESSION_TRANSACTED);

			/*
			 * Criando Queue e Consumidor
			 */
			Destination fila = session.createQueue(Config.FILA_LINHAS);
			MessageConsumer consumer = session.createConsumer(fila);

			/*
			 * Criando Topic e Publisher
			 */
			Destination topico = session.createTopic(Config.TOPICO_PALAVRAS);
			MessageProducer publisher = session.createProducer(topico);
			publisher.setDeliveryMode(DeliveryMode.PERSISTENT);

			System.out.println(nome + " aguardando linhas...");

			while (true) {

				/*
				 * Recebendo a próxima linha (bloqueia até haver uma disponível)
				 */
				Message message = consumer.receive();
				if (!(message instanceof TextMessage)) {
					session.commit();
					continue;
				}

				TextMessage linha = (TextMessage) message;
				String[] palavras = linha.getStringProperty(Config.PROP_PALAVRAS).split(",");

				/*
				 * Contando as palavras-chave e montando a mensagem de resultado.
				 * MapMessage guarda pares nome -> valor: palavra -> quantidade na linha.
				 */
				MapMessage resultado = session.createMapMessage();
				String[] tokens = tokenizar(linha.getText());
				for (String palavra : palavras) {
					resultado.setInt(palavra, contar(tokens, palavra));
				}
				resultado.setStringProperty(Config.PROP_EXECUCAO, linha.getStringProperty(Config.PROP_EXECUCAO));
				resultado.setIntProperty(Config.PROP_LINHA, linha.getIntProperty(Config.PROP_LINHA));
				resultado.setStringProperty(Config.PROP_CONTADOR, nome);

				/*
				 * Publicando no tópico e confirmando a transação
				 */
				publisher.send(resultado);
				session.commit();
			}

		} catch (JMSException e) {
			e.printStackTrace();
		}
	}

	/*
	 * Separa a linha em palavras, ignorando pontuação e maiúsculas/minúsculas
	 */
	static String[] tokenizar(String linha) {
		return linha.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
	}

	static int contar(String[] tokens, String palavra) {
		int total = 0;
		for (String token : tokens) {
			if (token.equals(palavra)) {
				total++;
			}
		}
		return total;
	}
}
