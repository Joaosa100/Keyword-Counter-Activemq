package mom;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import javax.jms.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

import org.apache.activemq.ActiveMQConnectionFactory;

/*
 * Dashboard (6) - Subscriber do Tópico de Palavras.
 *
 * Recebe as contagens publicadas pelos Contadores e atualiza na interface
 * o total de cada palavra-chave e quantas linhas cada Contador processou.
 */
public class Dashboard extends JFrame implements MessageListener {

	/*
	 * Identificação da assinatura durável no Broker.
	 * Com ela, o Broker guarda as mensagens publicadas enquanto o Dashboard
	 * estiver fechado e as entrega quando ele se conectar de novo.
	 */
	private static final String CLIENT_ID = "dashboard";
	private static final String ASSINATURA = "dashboard-palavras";

	private final DefaultTableModel tabelaPalavras = new DefaultTableModel(new Object[] { "Palavra-chave", "Ocorrências" }, 0);
	private final DefaultTableModel tabelaContadores = new DefaultTableModel(new Object[] { "Contador", "Linhas processadas" }, 0);
	private final JLabel rotuloExecucao = new JLabel("Execução: aguardando mensagens...");
	private final JLabel rotuloLinhas = new JLabel("Linhas processadas: 0");

	/* Estado acumulado; só é acessado pela thread da interface (Swing) */
	private final Map<String, Integer> totais = new LinkedHashMap<>();
	private final Map<String, Integer> linhasPorContador = new TreeMap<>();
	private String execucaoAtual;
	private int linhasProcessadas;

	public static void main(String[] args) {
		SwingUtilities.invokeLater(() -> {
			Dashboard dashboard = new Dashboard();
			dashboard.setVisible(true);
			dashboard.conectar();
		});
	}

	public Dashboard() {
		super("Dashboard - Contagem de Palavras-chave");
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		setSize(560, 480);
		setLocationRelativeTo(null);

		JPanel topo = new JPanel(new GridLayout(2, 1));
		topo.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		rotuloLinhas.setFont(rotuloLinhas.getFont().deriveFont(Font.BOLD, 14f));
		topo.add(rotuloExecucao);
		topo.add(rotuloLinhas);

		JTable palavras = new JTable(tabelaPalavras);
		palavras.setFont(palavras.getFont().deriveFont(16f));
		palavras.setRowHeight(24);
		palavras.setEnabled(false);

		JTable contadores = new JTable(tabelaContadores);
		contadores.setEnabled(false);

		JSplitPane centro = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
				comTitulo(new JScrollPane(palavras), "Palavras-chave"),
				comTitulo(new JScrollPane(contadores), "Contadores (workers)"));
		centro.setResizeWeight(0.65);

		add(topo, BorderLayout.NORTH);
		add(centro, BorderLayout.CENTER);
	}

	private static JComponent comTitulo(JComponent componente, String titulo) {
		componente.setBorder(BorderFactory.createTitledBorder(titulo));
		return componente;
	}

	private void conectar() {
		try {

			/*
			 * Estabelecendo conexão com o Servidor JMS.
			 * O clientID identifica este assinante para a assinatura durável.
			 */
			ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(Config.URL);
			Connection connection = connectionFactory.createConnection();
			connection.setClientID(CLIENT_ID);

			/*
			 * Criando Session
			 */
			Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

			/*
			 * Criando Topic
			 */
			Topic topico = session.createTopic(Config.TOPICO_PALAVRAS);

			/*
			 * Criando Subscriber durável e setando Listener
			 */
			MessageConsumer subscriber = session.createDurableSubscriber(topico, ASSINATURA);
			subscriber.setMessageListener(this);

			connection.start();

		} catch (JMSException e) {
			e.printStackTrace();
			JOptionPane.showMessageDialog(this,
					"Não foi possível conectar ao Broker em " + Config.URL + "\n" + e.getMessage(),
					"Erro de conexão", JOptionPane.ERROR_MESSAGE);
		}
	}

	/*
	 * Chamado pelo cliente JMS a cada contagem publicada no tópico.
	 * Os dados são lidos aqui e a interface é atualizada na thread do Swing.
	 */
	@Override
	public void onMessage(Message message) {
		if (!(message instanceof MapMessage)) {
			return;
		}
		try {
			MapMessage resultado = (MapMessage) message;
			String execucao = resultado.getStringProperty(Config.PROP_EXECUCAO);
			String contador = resultado.getStringProperty(Config.PROP_CONTADOR);

			Map<String, Integer> contagens = new LinkedHashMap<>();
			Enumeration<?> nomes = resultado.getMapNames();
			while (nomes.hasMoreElements()) {
				String palavra = (String) nomes.nextElement();
				contagens.put(palavra, resultado.getInt(palavra));
			}

			SwingUtilities.invokeLater(() -> atualizar(execucao, contador, contagens));
		} catch (JMSException e) {
			e.printStackTrace();
		}
	}

	private void atualizar(String execucao, String contador, Map<String, Integer> contagens) {

		/* Uma nova execução do Cliente zera o painel */
		if (!execucao.equals(execucaoAtual)) {
			execucaoAtual = execucao;
			totais.clear();
			linhasPorContador.clear();
			linhasProcessadas = 0;
			rotuloExecucao.setText("Execução: " + execucao);
		}

		contagens.forEach((palavra, quantidade) -> totais.merge(palavra, quantidade, Integer::sum));
		linhasPorContador.merge(contador, 1, Integer::sum);
		linhasProcessadas++;

		tabelaPalavras.setRowCount(0);
		totais.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.forEach(e -> tabelaPalavras.addRow(new Object[] { e.getKey(), e.getValue() }));

		tabelaContadores.setRowCount(0);
		linhasPorContador.forEach((nome, linhas) -> tabelaContadores.addRow(new Object[] { nome, linhas }));

		rotuloLinhas.setText("Linhas processadas: " + linhasProcessadas);
	}
}
