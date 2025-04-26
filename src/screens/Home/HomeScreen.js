import React, { useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  TextInput,
  Alert,
  Image,
} from 'react-native';

const HomeScreen = ({ navigation }) => {
  const [connectionCode, setConnectionCode] = useState('');

  const startMonitoring = () => {
    if (!connectionCode || connectionCode.length !== 6) {
      Alert.alert('输入错误', '请输入有效的6位连接码');
      return;
    }
    
    navigation.navigate('Monitoring', { targetUserId: connectionCode });
  };

  const startBroadcasting = () => {
    navigation.navigate('Broadcast');
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>屏幕监控</Text>
        <Text style={styles.subtitle}>连接两台设备，实时监控屏幕</Text>
      </View>
      
      <View style={styles.content}>
        <View style={styles.card}>
          <Image
            source={require('../../assets/monitor-icon.png')}
            style={styles.cardIcon}
          />
          <Text style={styles.cardTitle}>监控模式</Text>
          <Text style={styles.cardDescription}>
            查看其他设备的屏幕内容
          </Text>
          <TextInput
            style={styles.input}
            placeholder="输入6位连接码"
            placeholderTextColor="#999"
            keyboardType="number-pad"
            maxLength={6}
            value={connectionCode}
            onChangeText={setConnectionCode}
          />
          <TouchableOpacity
            style={styles.button}
            onPress={startMonitoring}
          >
            <Text style={styles.buttonText}>开始监控</Text>
          </TouchableOpacity>
        </View>
        
        <View style={styles.divider}>
          <View style={styles.dividerLine} />
          <Text style={styles.dividerText}>或者</Text>
          <View style={styles.dividerLine} />
        </View>
        
        <View style={styles.card}>
          <Image
            source={require('../../assets/broadcast-icon.png')}
            style={styles.cardIcon}
          />
          <Text style={styles.cardTitle}>广播模式</Text>
          <Text style={styles.cardDescription}>
            将您的屏幕内容分享给其他设备
          </Text>
          <TouchableOpacity
            style={[styles.button, styles.broadcastButton]}
            onPress={startBroadcasting}
          >
            <Text style={styles.buttonText}>开始广播</Text>
          </TouchableOpacity>
        </View>
      </View>
      
      <TouchableOpacity
        style={styles.settingsButton}
        onPress={() => navigation.navigate('Settings')}
      >
        <Text style={styles.settingsButtonText}>设置</Text>
      </TouchableOpacity>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f8f9fa',
  },
  header: {
    padding: 20,
    alignItems: 'center',
    marginTop: 20,
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    color: '#333',
  },
  subtitle: {
    fontSize: 16,
    color: '#666',
    marginTop: 5,
  },
  content: {
    flex: 1,
    padding: 20,
  },
  card: {
    backgroundColor: '#fff',
    borderRadius: 10,
    padding: 20,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
    alignItems: 'center',
  },
  cardIcon: {
    width: 60,
    height: 60,
    marginBottom: 15,
  },
  cardTitle: {
    fontSize: 20,
    fontWeight: 'bold',
    color: '#333',
    marginBottom: 10,
  },
  cardDescription: {
    fontSize: 14,
    color: '#666',
    textAlign: 'center',
    marginBottom: 20,
  },
  input: {
    width: '100%',
    height: 50,
    borderWidth: 1,
    borderColor: '#ddd',
    borderRadius: 8,
    paddingHorizontal: 15,
    fontSize: 16,
    marginBottom: 20,
    textAlign: 'center',
    letterSpacing: 5,
  },
  button: {
    width: '100%',
    height: 50,
    backgroundColor: '#007bff',
    borderRadius: 8,
    justifyContent: 'center',
    alignItems: 'center',
  },
  broadcastButton: {
    backgroundColor: '#28a745',
  },
  buttonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: 'bold',
  },
  divider: {
    flexDirection: 'row',
    alignItems: 'center',
    marginVertical: 20,
  },
  dividerLine: {
    flex: 1,
    height: 1,
    backgroundColor: '#ddd',
  },
  dividerText: {
    paddingHorizontal: 10,
    color: '#999',
  },
  settingsButton: {
    position: 'absolute',
    top: 20,
    right: 20,
    padding: 10,
  },
  settingsButtonText: {
    color: '#007bff',
    fontSize: 16,
  },
});

export default HomeScreen;