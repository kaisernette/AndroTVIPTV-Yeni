(function(){
  'use strict';
  window.KaiserDeviceInfo = {
    readDuid: function(){
      var result = { supported: false, duid: '' };
      try {
        if (window.webapis && webapis.productinfo && typeof webapis.productinfo.getDuid === 'function') {
          result.supported = true;
          result.duid = webapis.productinfo.getDuid() || '';
        }
      } catch (e) {
        result.error = String(e && e.message ? e.message : e);
      }
      return result;
    }
  };
}());
