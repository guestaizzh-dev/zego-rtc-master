<template>
  <div class="join">
    <div class="content">
      <input
        type="text"
        v-model.trim="channelName"
        placeholder="请输入房间号"
      />
      <button :disabled="!isSupport || !channelName" class="submit-btn" @click="handleSubmit">
        加入房间
      </button>
      <div class="errorMsg" v-show="!isSupport">
        当前浏览器不支持 WebRTC，请使用最新版 Chrome、Edge 或 Safari
      </div>
    </div>
  </div>
</template>

<script>
  import { message } from '../../components/message';
  import { checkBrowser } from '../../common';

  export default {
    name: 'join',
    data() {
      return {
        channelName: '',
        isSupport: true,
      };
    },
    mounted() {
      this.isSupport = !checkBrowser('ie');
    },
    methods: {
      handleSubmit() {
        if (!this.channelName) {
          message('房间号不能为空');
          return;
        }
        this.$router.push({
          path: '/single',
          query: {
            channelName: this.channelName,
            role: 'debug',
          },
        });
      },
    },
  };
</script>

<style scoped lang="less">
.join {
  min-height: 100vh;
  background: #f4f6f8;
  display: flex;
  align-items: center;
  justify-content: center;

  .content {
    width: 320px;
  }

  input {
    width: 100%;
    height: 44px;
    box-sizing: border-box;
    border: 1px solid #cfd6df;
    border-radius: 4px;
    padding: 0 12px;
    font-size: 16px;
    outline: none;
  }

  .submit-btn {
    width: 100%;
    height: 44px;
    margin-top: 16px;
    border: 0;
    border-radius: 4px;
    background: #347c98;
    color: #fff;
    font-size: 16px;
    cursor: pointer;

    &:disabled {
      cursor: not-allowed;
      background: #9aa7b2;
    }
  }

  .errorMsg {
    margin-top: 12px;
    color: #c0392b;
    line-height: 22px;
  }
}
</style>
